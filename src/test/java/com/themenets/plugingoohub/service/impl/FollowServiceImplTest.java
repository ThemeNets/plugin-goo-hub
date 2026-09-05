package com.themenets.plugingoohub.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.themenets.plugingoohub.extension.FedFollow;
import com.themenets.plugingoohub.extension.FedSite;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    @Mock
    ReactiveExtensionClient client;

    private FollowServiceImpl service() {
        return new FollowServiceImpl(client);
    }

    private static FedSite registeredSite(String name) {
        FedSite site = new FedSite();
        Metadata md = new Metadata();
        md.setName(name);
        site.setMetadata(md);
        FedSite.FedSiteSpec spec = new FedSite.FedSiteSpec();
        spec.setSiteUrl("https://" + name + ".example.com");
        spec.setApproved(true);
        site.setSpec(spec);
        site.setStatus(new FedSite.FedSiteStatus());
        return site;
    }

    @Test
    void 关注已收录站点创建订阅关系() {
        when(client.fetch(FedSite.class, "fed-abc")).thenReturn(Mono.just(registeredSite("fed-abc")));
        when(client.fetch(FedFollow.class, FollowServiceImpl.followName("admin", "fed-abc")))
            .thenReturn(Mono.empty());
        when(client.create(any(FedFollow.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().follow("admin", "fed-abc")).verifyComplete();

        ArgumentCaptor<FedFollow> captor = ArgumentCaptor.forClass(FedFollow.class);
        verify(client).create(captor.capture());
        FedFollow f = captor.getValue();
        assertThat(f.getSpec().getUsername()).isEqualTo("admin");
        assertThat(f.getSpec().getSiteName()).isEqualTo("fed-abc");
        assertThat(f.getSpec().getCreatedAt()).isNotNull();
        assertThat(f.getMetadata().getName()).startsWith("fedfollow-");
    }

    @Test
    void 关注未收录站点报错() {
        when(client.fetch(FedSite.class, "fed-ghost")).thenReturn(Mono.empty());
        StepVerifier.create(service().follow("admin", "fed-ghost"))
            .expectError(java.util.NoSuchElementException.class)
            .verify();
        verify(client, never()).create(any(FedFollow.class));
    }

    @Test
    void 重复关注幂等不重建() {
        when(client.fetch(FedSite.class, "fed-abc")).thenReturn(Mono.just(registeredSite("fed-abc")));
        FedFollow existing = new FedFollow();
        Metadata m = new Metadata();
        m.setName(FollowServiceImpl.followName("admin", "fed-abc"));
        existing.setMetadata(m);
        existing.setSpec(new FedFollow.FedFollowSpec());
        when(client.fetch(FedFollow.class, m.getName())).thenReturn(Mono.just(existing));

        StepVerifier.create(service().follow("admin", "fed-abc")).verifyComplete();
        verify(client, never()).create(any(FedFollow.class));
    }

    @Test
    void 取消关注删除订阅关系() {
        FedFollow existing = new FedFollow();
        Metadata m = new Metadata();
        m.setName(FollowServiceImpl.followName("admin", "fed-abc"));
        existing.setMetadata(m);
        when(client.fetch(FedFollow.class, m.getName())).thenReturn(Mono.just(existing));
        when(client.delete(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service().unfollow("admin", "fed-abc")).verifyComplete();
        verify(client).delete(existing);
    }

    @Test
    void 取消未关注的站点静默成功() {
        when(client.fetch(FedFollow.class, FollowServiceImpl.followName("admin", "fed-x")))
            .thenReturn(Mono.empty());
        StepVerifier.create(service().unfollow("admin", "fed-x")).verifyComplete();
        verify(client, never()).delete(any(FedFollow.class));
    }

    @Test
    void 我的关注列表映射站点名() {
        FedFollow f1 = follow("admin", "fed-a");
        FedFollow f2 = follow("admin", "fed-b");
        FedFollow f3 = follow("admin", ""); // 空站点名 → 过滤
        when(client.listBy(eq(com.themenets.plugingoohub.extension.FedFollow.class),
                any(ListOptions.class), any(PageRequest.class)))
            .thenReturn(Mono.just(new ListResult<>(1, 3, 3, List.of(f1, f2, f3))));

        StepVerifier.create(service().listFollows("admin"))
            .assertNext(names -> assertThat(names).containsExactly("fed-a", "fed-b"))
            .verifyComplete();
    }

    @Test
    void 订阅关系锚点稳定() {
        String n1 = FollowServiceImpl.followName("admin", "fed-abc");
        String n2 = FollowServiceImpl.followName("admin", "fed-abc");
        assertThat(n1).isEqualTo(n2);
        assertThat(n1).startsWith("fedfollow-");
        assertThat(FollowServiceImpl.followName("admin", "fed-abc"))
            .isNotEqualTo(FollowServiceImpl.followName("admin", "fed-xyz"));
    }

    // ---------- 构造工具 ----------

    private static FedFollow follow(String username, String siteName) {
        FedFollow f = new FedFollow();
        Metadata m = new Metadata();
        m.setName(FollowServiceImpl.followName(username, siteName));
        f.setMetadata(m);
        f.setSpec(new FedFollow.FedFollowSpec());
        f.getSpec().setUsername(username);
        f.getSpec().setSiteName(siteName);
        return f;
    }
}
