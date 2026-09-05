package com.themenets.plugingoohub.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.themenets.plugingoohub.domain.vo.FedItemVo;
import com.themenets.plugingoohub.extension.FedItem;
import com.themenets.plugingoohub.extension.FedSite;
import com.themenets.plugingoohub.service.CapabilitiesClient;
import com.themenets.plugingoohub.service.ContentSanitizer;
import com.themenets.plugingoohub.service.TimelinePuller;
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
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class AggregatorServiceImplTest {

    private static final String SITE = "fed-test";
    private static final String SITE_URL = "https://sea.example.com";

    @Mock
    ReactiveExtensionClient client;

    @Mock
    TimelinePuller puller;

    @Mock
    CapabilitiesClient capClient;

    private final ContentSanitizer sanitizer = new ContentSanitizer();

    private AggregatorServiceImpl service() {
        return new AggregatorServiceImpl(client, puller, sanitizer, capClient);
    }

    private static FedSite site() {
        FedSite site = new FedSite();
        Metadata md = new Metadata();
        md.setName(SITE);
        site.setMetadata(md);
        FedSite.FedSiteSpec spec = new FedSite.FedSiteSpec();
        spec.setSiteUrl(SITE_URL);
        spec.setApproved(true);
        site.setSpec(spec);
        site.setStatus(new FedSite.FedSiteStatus());
        site.getStatus().setRoutePrefix("/goo");
        return site;
    }

    private static TimelinePuller.PulledItem item(String sourceName, String html,
                                                  Instant createdAt) {
        return new TimelinePuller.PulledItem(
            "note", sourceName, "", html, html,
            "ashan", "阿山", SITE_URL + "/goo/u/ashan", createdAt);
    }

    @Test
    void 同步条目入库并推进游标() {
        FedSite site = site();
        when(client.fetch(FedSite.class, SITE)).thenReturn(Mono.just(site));
        Instant t1 = Instant.parse("2026-09-05T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-05T11:30:00Z");
        when(puller.pullSince(eq(SITE_URL), eq("/goo"), eq((Instant) null), eq(50)))
            .thenReturn(Mono.just(new TimelinePuller.PulledDelta(
                List.of(item("note-a", "<p>你好</p><script>x</script>", t1),
                        item("note-b", "<b>第二条</b>", t2)),
                List.of())));
        lenient().when(capClient.fetch(SITE_URL)).thenReturn(Mono.empty());
        when(client.fetch(eq(FedItem.class), any(String.class))).thenReturn(Mono.empty());
        when(client.create(any(FedItem.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(client.update(any(FedSite.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().syncSite(SITE))
            .assertNext(vo -> assertThat(vo.name()).isEqualTo(SITE))
            .verifyComplete();

        ArgumentCaptor<FedItem> captor = ArgumentCaptor.forClass(FedItem.class);
        verify(client, org.mockito.Mockito.times(2)).create(captor.capture());
        FedItem first = captor.getAllValues().get(0);
        assertThat(first.getSpec().getSiteName()).isEqualTo(SITE);
        assertThat(first.getSpec().getSourceName()).isEqualTo("note-a");
        assertThat(first.getSpec().getKind()).isEqualTo("note");
        assertThat(first.getSpec().getHtml()).doesNotContain("<script>");
        assertThat(first.getSpec().getHtml()).contains("你好");
        // 游标推进到本批最大 sourceCreatedAt
        assertThat(site.getStatus().getLastSyncCursor()).isEqualTo(t2);
        assertThat(site.getStatus().getLastError()).isNull();
        verify(client).update(any(FedSite.class));
    }

    @Test
    void 重复条目幂等覆盖不重建() {
        FedSite site = site();
        when(client.fetch(FedSite.class, SITE)).thenReturn(Mono.just(site));
        Instant t1 = Instant.parse("2026-09-05T10:00:00Z");
        when(puller.pullSince(eq(SITE_URL), eq("/goo"), eq(null), eq(50)))
            .thenReturn(Mono.just(new TimelinePuller.PulledDelta(
                List.of(item("note-a", "<p>新版本</p>", t1)), List.of())));
        lenient().when(capClient.fetch(SITE_URL)).thenReturn(Mono.empty());
        FedItem cached = new FedItem();
        Metadata md = new Metadata();
        md.setName(AggregatorServiceImpl.itemAnchor(SITE, "note-a"));
        cached.setMetadata(md);
        cached.setSpec(new FedItem.FedItemSpec());
        cached.setStatus(new FedItem.FedItemStatus());
        cached.getSpec().setSiteName(SITE);
        cached.getSpec().setSourceName("note-a");
        when(client.fetch(FedItem.class, cached.getMetadata().getName()))
            .thenReturn(Mono.just(cached));
        when(client.update(any(FedItem.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(client.update(any(FedSite.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().syncSite(SITE))
            .expectNextCount(1)
            .verifyComplete();

        verify(client, never()).create(any(FedItem.class));
        ArgumentCaptor<FedItem> captor = ArgumentCaptor.forClass(FedItem.class);
        verify(client).update(captor.capture());
        assertThat(captor.getValue().getSpec().getHtml()).contains("新版本");
        assertThat(captor.getValue().getStatus().getFirstSeenAt()).isNotNull();
    }

    @Test
    void 墓碑失效已缓存条目() {
        FedSite site = site();
        when(client.fetch(FedSite.class, SITE)).thenReturn(Mono.just(site));
        when(puller.pullSince(eq(SITE_URL), eq("/goo"), eq(null), eq(50)))
            .thenReturn(Mono.just(new TimelinePuller.PulledDelta(
                List.of(),
                List.of(new TimelinePuller.PulledTombstone(
                    "note-a", "note", Instant.parse("2026-09-05T12:00:00Z"))))));
        lenient().when(capClient.fetch(SITE_URL)).thenReturn(Mono.empty());
        FedItem cached = new FedItem();
        Metadata md = new Metadata();
        md.setName(AggregatorServiceImpl.itemAnchor(SITE, "note-a"));
        cached.setMetadata(md);
        cached.setSpec(new FedItem.FedItemSpec());
        cached.setStatus(new FedItem.FedItemStatus());
        when(client.fetch(FedItem.class, md.getName())).thenReturn(Mono.just(cached));
        when(client.update(any(FedItem.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(client.update(any(FedSite.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().syncSite(SITE))
            .expectNextCount(1)
            .verifyComplete();

        ArgumentCaptor<FedItem> captor = ArgumentCaptor.forClass(FedItem.class);
        verify(client).update(captor.capture());
        assertThat(captor.getValue().getStatus().getDeleted()).isTrue();
        assertThat(captor.getValue().getStatus().getDeletedAt()).isNotNull();
    }

    @Test
    void 同步失败记lastError并整体报错() {
        FedSite site = site();
        when(client.fetch(FedSite.class, SITE)).thenReturn(Mono.just(site));
        when(puller.pullSince(eq(SITE_URL), eq("/goo"), eq(null), eq(50)))
            .thenReturn(Mono.error(new IllegalStateException("对端 since 增量返回 HTTP 500")));
        when(client.update(any(FedSite.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().syncSite(SITE))
            .expectError(IllegalStateException.class)
            .verify();

        assertThat(site.getStatus().getLastError()).contains("500");
        assertThat(site.getStatus().getLastSyncAt()).isNotNull();
        verify(client).update(any(FedSite.class));
    }

    @Test
    void 站点不存在报错() {
        when(client.fetch(FedSite.class, "fed-none")).thenReturn(Mono.empty());
        StepVerifier.create(service().syncSite("fed-none"))
            .expectError(java.util.NoSuchElementException.class)
            .verify();
    }

    @Test
    void 全网读端过滤未失效条目() {
        FedItem item1 = cachedItem("note-a", "note");
        FedItem item2 = cachedItem("note-b", "note");
        item2.getStatus().setDeleted(true); // 已失效，应被过滤条件排除（由索引查询承担）
        FedItem item3 = cachedItem("topic-a", "topic");
        when(client.listBy(eq(FedItem.class), any(ListOptions.class), any(PageRequest.class)))
            .thenReturn(Mono.just(new ListResult<>(1, 2, 2, List.of(item1, item3))));

        StepVerifier.create(service().listItems(null, null, 1, 30))
            .assertNext(vo -> {
                assertThat(vo.items()).extracting(FedItemVo::sourceName)
                    .containsExactly("note-a", "topic-a");
                assertThat(vo.hasMore()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void hasMore计算与游标翻页() {
        FedItem item1 = cachedItem("note-a", "note");
        when(client.listBy(eq(FedItem.class), any(ListOptions.class), any(PageRequest.class)))
            .thenReturn(Mono.just(new ListResult<>(1, 1, 5, List.of(item1))));

        StepVerifier.create(service().listItems("fed-test", "note", 1, 1))
            .assertNext(vo -> {
                assertThat(vo.hasMore()).isTrue();
                assertThat(vo.nextCursor()).isNotNull();
            })
            .verifyComplete();
    }

    private static FedItem cachedItem(String sourceName, String kind) {
        FedItem item = new FedItem();
        Metadata md = new Metadata();
        md.setName("feditem-" + sourceName);
        item.setMetadata(md);
        item.setSpec(new FedItem.FedItemSpec());
        item.setStatus(new FedItem.FedItemStatus());
        item.getSpec().setSiteName(SITE);
        item.getSpec().setSiteUrl(SITE_URL);
        item.getSpec().setKind(kind);
        item.getSpec().setSourceName(sourceName);
        item.getSpec().setTitle(sourceName);
        item.getSpec().setExcerpt("摘要 " + sourceName);
        item.getSpec().setHtml("<p>内容</p>");
        item.getSpec().setAuthorName("ashan");
        item.getSpec().setAuthorDisplay("阿山");
        item.getSpec().setContentUrl(SITE_URL + "/goo/u/ashan");
        item.getSpec().setSourceCreatedAt(Instant.parse("2026-09-05T10:00:00Z"));
        item.getStatus().setFirstSeenAt(Instant.parse("2026-09-05T10:05:00Z"));
        return item;
    }
}
