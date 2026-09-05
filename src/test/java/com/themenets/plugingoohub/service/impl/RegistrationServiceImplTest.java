package com.themenets.plugingoohub.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.themenets.plugingoohub.domain.vo.SiteItemVo;
import com.themenets.plugingoohub.extension.FedSite;
import com.themenets.plugingoohub.service.CapabilitiesClient;
import com.themenets.plugingoohub.service.RegistrationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceImplTest {

    private static final String VALID_CAP_JSON = """
        {"feedVersion": 1, "kinds": ["note", "topic"],
         "site": {"title": "海崖站", "subtitle": "海崖社区", "description": "海边社区",
                  "url": "https://sea.example.com/"},
         "routePrefix": "/goo", "noteCount": 12, "topicCount": 3}
        """;

    @Mock
    ReactiveExtensionClient client;

    @Mock
    CapabilitiesClient capClient;

    private RegistrationService service() {
        return new RegistrationServiceImpl(client, capClient);
    }

    @Test
    void 登记成功自动验证并落库() {
        String siteUrl = "https://sea.example.com";
        String name = RegistrationServiceImpl.siteName(siteUrl);
        when(capClient.fetch(siteUrl))
            .thenReturn(Mono.just(CapabilitiesClient.parse(VALID_CAP_JSON)));
        when(client.fetch(FedSite.class, name)).thenReturn(Mono.empty());
        when(client.create(any(FedSite.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().register("sea.example.com/", ""))
            .assertNext(vo -> {
                assertThat(vo.title()).isEqualTo("海崖站");
                assertThat(vo.url()).isEqualTo("https://sea.example.com/");
                assertThat(vo.noteCount()).isEqualTo(12);
                assertThat(vo.topicCount()).isEqualTo(3);
                assertThat(vo.lastSyncAt()).isNotNull();
            })
            .verifyComplete();

        ArgumentCaptor<FedSite> captor = ArgumentCaptor.forClass(FedSite.class);
        verify(client).create(captor.capture());
        FedSite site = captor.getValue();
        assertThat(site.getSpec().getSiteUrl()).isEqualTo(siteUrl);
        assertThat(site.getSpec().getApproved()).isTrue();
        assertThat(site.getStatus().getTitle()).isEqualTo("海崖站");
        assertThat(site.getStatus().getRoutePrefix()).isEqualTo("/goo");
        assertThat(site.getStatus().getNoteCount()).isEqualTo(12);
        assertThat(site.getStatus().getFeedVersion()).isEqualTo(1);
    }

    @Test
    void 重复登记幂等更新不重建() {
        String siteUrl = "https://sea.example.com";
        String name = RegistrationServiceImpl.siteName(siteUrl);
        when(capClient.fetch(siteUrl))
            .thenReturn(Mono.just(CapabilitiesClient.parse(VALID_CAP_JSON)));
        FedSite existing = existingSite(name, siteUrl);
        when(client.fetch(FedSite.class, name))
            .thenReturn(Mono.just(existing));
        when(client.update(any(FedSite.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().register("https://sea.example.com/", "admin"))
            .assertNext(vo -> assertThat(vo.title()).isEqualTo("海崖站"))
            .verifyComplete();

        verify(client).update(any(FedSite.class));
        verify(client, never()).create(any(FedSite.class));
    }

    @Test
    void 目标站不可达时报错不落库() {
        when(capClient.fetch("https://sea.example.com"))
            .thenReturn(Mono.error(new IllegalStateException(
                "目标站 capabilities 返回 HTTP 404")));

        StepVerifier.create(service().register("sea.example.com", ""))
            .expectError(IllegalStateException.class)
            .verify();

        verify(client, never()).create(any(FedSite.class));
        verify(client, never()).update(any(FedSite.class));
    }

    @Test
    void feedVersion不兼容时报错不落库() {
        when(capClient.fetch("https://sea.example.com"))
            .thenReturn(Mono.just(new CapabilitiesClient.CapabilitiesVo(
                2, java.util.List.of("note"), "x", "", "", "", "/goo", 1, 1)));

        StepVerifier.create(service().register("sea.example.com", ""))
            .expectError(IllegalStateException.class)
            .verify();

        verify(client, never()).create(any(FedSite.class));
    }

    @Test
    void 空地址报参数错误() {
        StepVerifier.create(service().register("  ", ""))
            .expectError(IllegalArgumentException.class)
            .verify();
    }

    @Test
    void 公开列表只回已收录站点并按标题排序() {
        FedSite approved1 = approvedSite("fed-a", "https://b.example.com", "B站");
        FedSite approved2 = approvedSite("fed-c", "https://a.example.com", "A站");
        FedSite pending = rejectedSite("fed-b", "https://c.example.com", "C站");
        when(client.listBy(eq(FedSite.class), any(ListOptions.class), any(run.halo.app.extension.PageRequest.class)))
            .thenReturn(Mono.just(new run.halo.app.extension.ListResult<>(
                1, 3, 3, List.of(approved1, pending, approved2))));

        StepVerifier.create(service().listSites())
            .assertNext(items -> {
                assertThat(items).extracting(SiteItemVo::title).containsExactly("A站", "B站");
                assertThat(items).extracting(SiteItemVo::name).containsExactly("fed-c", "fed-a");
            })
            .verifyComplete();
    }

    @Test
    void siteUrl规范化补scheme去尾斜杠() {
        assertThat(RegistrationServiceImpl.normalizeSiteUrl("sea.example.com"))
            .isEqualTo("https://sea.example.com");
        assertThat(RegistrationServiceImpl.normalizeSiteUrl("https://SEA.example.com/"))
            .isEqualTo("https://sea.example.com");
        assertThat(RegistrationServiceImpl.normalizeSiteUrl("http://a.example.com:8080/x/"))
            .isEqualTo("http://a.example.com:8080/x");
        assertThat(RegistrationServiceImpl.siteName("https://sea.example.com"))
            .startsWith("fed-");
    }

    // ---------- 构造工具 ----------

    private static FedSite existingSite(String name, String siteUrl) {
        FedSite site = new FedSite();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        site.setMetadata(metadata);
        FedSite.FedSiteSpec spec = new FedSite.FedSiteSpec();
        spec.setSiteUrl(siteUrl);
        spec.setApproved(true);
        site.setSpec(spec);
        site.setStatus(new FedSite.FedSiteStatus());
        return site;
    }

    private static FedSite approvedSite(String name, String siteUrl, String title) {
        FedSite site = existingSite(name, siteUrl);
        site.getSpec().setApproved(true);
        site.getStatus().setTitle(title);
        site.getStatus().setSiteUrl(siteUrl);
        site.getStatus().setLastSyncAt(Instant.now());
        return site;
    }

    private static FedSite rejectedSite(String name, String siteUrl, String title) {
        FedSite site = approvedSite(name, siteUrl, title);
        site.getSpec().setApproved(false);
        return site;
    }
}
