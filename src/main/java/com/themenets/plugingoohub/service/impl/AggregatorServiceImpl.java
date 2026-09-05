package com.themenets.plugingoohub.service.impl;

import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_DELETED;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_KIND;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_SITE_NAME;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_SOURCE_CREATED_AT;
import static com.themenets.plugingoohub.constants.HubRoutes.SITE_APPROVED;
import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;

import com.themenets.plugingoohub.domain.vo.CursorResultVo;
import com.themenets.plugingoohub.domain.vo.FedItemVo;
import com.themenets.plugingoohub.domain.vo.SiteItemVo;
import com.themenets.plugingoohub.extension.FedItem;
import com.themenets.plugingoohub.extension.FedSite;
import com.themenets.plugingoohub.service.AggregatorService;
import com.themenets.plugingoohub.service.CapabilitiesClient;
import com.themenets.plugingoohub.service.ContentSanitizer;
import com.themenets.plugingoohub.service.TimelinePuller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Condition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 聚合器实现（阶段二）：对 plugin-goo 公开地基的消费端。
 * <p>
 * 同步流程（单站）：
 * <ol>
 *   <li>读 FedSite（拿 siteUrl + lastSyncCursor）；</li>
 *   <li>{@code timelines/local?since=<cursor>} 拉增量（失败 → 记 lastError 并整体报错）；</li>
 *   <li>capabilities 刷新站点元数据（失败不中断内容入库）；</li>
 *   <li>items 按 (siteName, sourceName) upsert（入库前 ContentSanitizer 消毒）；</li>
 *   <li>tombstones 按 (siteName, sourceName) 置 status.deleted=true（软失效）；</li>
 *   <li>推进 lastSyncCursor = 本批最大 sourceCreatedAt，写 lastSyncAt/lastError。</li>
 * </ol>
 * 全网读端：未失效条目按 (sourceCreatedAt, name) 倒序混排，页码模式分页。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregatorServiceImpl implements AggregatorService {

    private static final String CREATION_TIMESTAMP = "metadata.creationTimestamp";
    /** 单批拉取上限（与对端 PublicTimelineEndpoint MAX_SIZE 对齐） */
    private static final int SYNC_BATCH = 50;

    private final ReactiveExtensionClient client;
    private final TimelinePuller puller;
    private final ContentSanitizer sanitizer;
    private final CapabilitiesClient capClient;

    // ---------- 同步 ----------

    @Override
    public Mono<SiteItemVo> syncSite(String siteName) {
        if (siteName == null || siteName.isBlank()) {
            return Mono.error(new IllegalArgumentException("siteName 必填"));
        }
        return client.fetch(FedSite.class, siteName)
            .switchIfEmpty(Mono.error(new NoSuchElementException("站点不存在: " + siteName)))
            .flatMap(site -> {
                String siteUrl = site.getSpec() == null ? null : site.getSpec().getSiteUrl();
                if (siteUrl == null || siteUrl.isBlank()) {
                    return Mono.error(new IllegalStateException("站点缺少 siteUrl: " + siteName));
                }
                String routePrefix = site.getStatus() == null
                    ? null : site.getStatus().getRoutePrefix();
                Instant cursor = site.getStatus() == null
                    ? null : site.getStatus().getLastSyncCursor();
                return puller.pullSince(siteUrl, routePrefix, cursor, SYNC_BATCH)
                    .defaultIfEmpty(TimelinePuller.PulledDelta.EMPTY)
                    .flatMap(delta -> refreshCap(site, siteUrl)          // 元数据刷新，失败不中断
                        .onErrorResume(e -> Mono.empty())
                        .then(ingestDelta(site, delta))
                        .flatMap(n -> finishSync(site, delta, n)))
                    .onErrorResume(e -> markError(site, e)
                        .then(Mono.<SiteItemVo>error(e)));
            });
    }

    @Override
    public Mono<Integer> syncAll() {
        return client.listBy(FedSite.class,
                ListOptions.builder()
                    .fieldQuery(equal(SITE_APPROVED, "true"))
                    .build(),
                PageRequestImpl.of(1, 1000, Sort.by(Sort.Order.asc("status.title"))))
            .flatMap(result -> Flux.fromIterable(result.getItems())
                .concatMap(site -> {
                    String name = site.getMetadata() == null
                        ? null : site.getMetadata().getName();
                    if (name == null || name.isBlank()) {
                        return Mono.just(0);
                    }
                    return syncSite(name)
                        .doOnError(e -> log.warn("聚合失败 {} — {}", name, e.toString()))
                        .onErrorResume(e -> Mono.empty())
                        .thenReturn(1)
                        .defaultIfEmpty(0);
                })
                .reduce(0, Integer::sum));
    }

    /** capabilities 刷新 FedSite 元数据（失败向上抛，由调用方决定是否中断） */
    private Mono<Void> refreshCap(FedSite site, String siteUrl) {
        return capClient.fetch(siteUrl)
            .flatMap(cap -> {
                if (site.getStatus() == null) {
                    site.setStatus(new FedSite.FedSiteStatus());
                }
                FedSite.FedSiteStatus st = site.getStatus();
                if (!cap.title().isBlank()) st.setTitle(cap.title());
                if (!cap.subtitle().isBlank()) st.setSubtitle(cap.subtitle());
                if (!cap.description().isBlank()) st.setDescription(cap.description());
                if (!cap.siteUrl().isBlank()) st.setSiteUrl(cap.siteUrl());
                if (!cap.routePrefix().isBlank()) st.setRoutePrefix(cap.routePrefix());
                st.setFeedVersion(cap.feedVersion());
                st.setKinds(cap.kinds());
                st.setNoteCount(cap.noteCount());
                st.setTopicCount(cap.topicCount());
                return Mono.empty();
            })
            .then();
    }

    /** 同步收尾：推进游标 + lastSyncAt + 清 lastError + 持久化，返回刷新后的站点 VO */
    private Mono<SiteItemVo> finishSync(FedSite site, TimelinePuller.PulledDelta delta,
                                        int ingested) {
        Instant maxTs = delta.items().stream()
            .map(TimelinePuller.PulledItem::createdAt)
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(null);
        if (site.getStatus() == null) {
            site.setStatus(new FedSite.FedSiteStatus());
        }
        // 游标只前进不回退（失败批次不拖回已推进位点）
        Instant cur = site.getStatus().getLastSyncCursor();
        if (maxTs != null && (cur == null || maxTs.isAfter(cur))) {
            site.getStatus().setLastSyncCursor(maxTs);
        }
        site.getStatus().setLastSyncAt(Instant.now());
        site.getStatus().setLastError(null);
        return client.update(site)
            .thenReturn(SiteItemVo.from(site))
            .onErrorResume(e -> {
                log.warn("FedSite 状态持久化失败 {} — {}",
                    site.getMetadata().getName(), e.toString());
                return Mono.just(SiteItemVo.from(site)); // VO 仍返回（内存态已更新）
            });
    }

    /** 同步失败：记 lastError + lastSyncAt + 持久化（持久化失败吞掉），错误继续向上 */
    private Mono<Void> markError(FedSite site, Throwable e) {
        if (site.getStatus() == null) {
            site.setStatus(new FedSite.FedSiteStatus());
        }
        site.getStatus().setLastError(e.getMessage() == null ? e.toString() : e.getMessage());
        site.getStatus().setLastSyncAt(Instant.now());
        return client.update(site).onErrorResume(x -> Mono.empty()).then();
    }

    // ---------- 入库 ----------

    private Mono<Integer> ingestDelta(FedSite site, TimelinePuller.PulledDelta delta) {
        return upsertItems(site, delta.items())
            .flatMap(n -> applyTombstones(site, delta.tombstones()).thenReturn(n));
    }

    private Mono<Integer> upsertItems(FedSite site, List<TimelinePuller.PulledItem> items) {
        return Flux.fromIterable(items)
            .concatMap(item -> upsertOne(site, item))
            .reduce(0, Integer::sum);
    }

    /** 单条 upsert：命中缓存覆盖（保 firstSeenAt），未命中新建；返回 1（跳过/失败 0） */
    private Mono<Integer> upsertOne(FedSite site, TimelinePuller.PulledItem item) {
        if (item == null || item.sourceName() == null || item.sourceName().isBlank()) {
            return Mono.just(0);
        }
        String anchor = itemAnchor(site.getMetadata().getName(), item.sourceName());
        // 缓存端消毒（对端 html 不可信：即便对端已 sanitize，hub 侧白名单再过一遍）
        String html = sanitizer.sanitizeHtml(item.html() == null ? "" : item.html());
        String excerpt = item.excerpt() == null || item.excerpt().isBlank()
            ? sanitizer.excerpt(html, 120)
            : item.excerpt();
        return client.fetch(FedItem.class, anchor)
            .flatMap(existing -> {
                applyItem(existing, site, item, html, excerpt);
                return client.update(existing).thenReturn(1);
            })
            .switchIfEmpty(Mono.defer(() -> {
                FedItem fresh = new FedItem();
                Metadata md = new Metadata();
                md.setName(anchor);
                fresh.setMetadata(md);
                fresh.setSpec(new FedItem.FedItemSpec());
                fresh.setStatus(new FedItem.FedItemStatus());
                applyItem(fresh, site, item, html, excerpt);
                fresh.getStatus().setFirstSeenAt(Instant.now());
                return client.create(fresh).thenReturn(1);
            }))
            .onErrorResume(e -> {
                log.warn("FedItem upsert 失败 {}:{} — {}",
                    site.getMetadata().getName(), item.sourceName(), e.toString());
                return Mono.just(0);
            });
    }

    private void applyItem(FedItem item, FedSite site, TimelinePuller.PulledItem pulled,
                           String html, String excerpt) {
        if (item.getSpec() == null) {
            item.setSpec(new FedItem.FedItemSpec());
        }
        if (item.getStatus() == null) {
            item.setStatus(new FedItem.FedItemStatus());
        }
        FedItem.FedItemSpec s = item.getSpec();
        s.setSiteName(site.getMetadata().getName());
        s.setSiteUrl(site.getStatus() == null ? null : site.getStatus().getSiteUrl());
        s.setKind(pulled.kind());
        s.setSourceName(pulled.sourceName());
        s.setTitle(pulled.title());
        s.setExcerpt(excerpt);
        s.setHtml(html);
        s.setAuthorName(pulled.authorName());
        s.setAuthorDisplay(pulled.authorDisplay());
        s.setContentUrl(pulled.contentUrl());
        s.setSourceCreatedAt(pulled.createdAt());
        // 复活：已失效条目若对端又推同名新内容（理论上 name 唯一，防御性处理）
        if (Boolean.TRUE.equals(item.getStatus().getDeleted())) {
            item.getStatus().setDeleted(false);
            item.getStatus().setDeletedAt(null);
        }
        if (item.getStatus().getFirstSeenAt() == null) {
            item.getStatus().setFirstSeenAt(Instant.now());
        }
    }

    /** 墓碑软失效：按 (siteName, sourceName) 找缓存条目置 deleted=true（幂等） */
    private Mono<Integer> applyTombstones(FedSite site,
                                          List<TimelinePuller.PulledTombstone> tombstones) {
        String siteName = site.getMetadata().getName();
        return Flux.fromIterable(tombstones)
            .concatMap(tb -> {
                if (tb.name() == null || tb.name().isBlank()) {
                    return Mono.just(0);
                }
                String anchor = itemAnchor(siteName, tb.name());
                return client.fetch(FedItem.class, anchor)
                    .flatMap(item -> {
                        if (item.getStatus() == null) {
                            item.setStatus(new FedItem.FedItemStatus());
                        }
                        if (Boolean.TRUE.equals(item.getStatus().getDeleted())) {
                            return Mono.just(0); // 已失效，幂等
                        }
                        item.getStatus().setDeleted(true);
                        item.getStatus().setDeletedAt(
                            tb.deletedAt() == null ? Instant.now() : tb.deletedAt());
                        return client.update(item).thenReturn(1);
                    })
                    .switchIfEmpty(Mono.just(0)) // 缓存没有该条目：无需失效（对端删得比同步快）
                    .onErrorResume(e -> {
                        log.warn("墓碑失效失败 {}:{} — {}", siteName, tb.name(), e.toString());
                        return Mono.just(0);
                    });
            })
            .reduce(0, Integer::sum);
    }

    /** 缓存条目锚点：feditem- + (siteName:sourceName) 摘要 */
    static String itemAnchor(String siteName, String sourceName) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((siteName + ":" + sourceName)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "feditem-" + java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return "feditem-" + Integer.toHexString((siteName + ":" + sourceName).hashCode());
        }
    }

    // ---------- 全网读端 ----------

    @Override
    public Mono<CursorResultVo> listItems(String siteName, String kind,
                                          int page, int size) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(equal(ITEM_DELETED, "false"));
        if (siteName != null && !siteName.isBlank()) {
            conditions.add(equal(ITEM_SITE_NAME, siteName));
        }
        if (kind != null && (kind.equalsIgnoreCase("note") || kind.equalsIgnoreCase("topic"))) {
            conditions.add(equal(ITEM_KIND, kind.toLowerCase()));
        }
        Condition query = conditions.size() == 1
            ? conditions.get(0) : and(conditions.get(0),
                conditions.subList(1, conditions.size()).toArray(new Condition[0]));
        int p = Math.max(1, page);
        int n = Math.max(1, Math.min(size, 100));
        return client.listBy(FedItem.class, ListOptions.builder()
                .fieldQuery(query)
                .build(),
            PageRequestImpl.of(p, n, Sort.by(
                Sort.Order.desc(ITEM_SOURCE_CREATED_AT),
                Sort.Order.desc(CREATION_TIMESTAMP))))
            .map(result -> {
                List<FedItemVo> vos = new ArrayList<>();
                for (FedItem item : result.getItems()) {
                    if (item.getSpec() != null) {
                        vos.add(toItemVo(item));
                    }
                }
                long total = result.getTotal();
                boolean hasMore = total > (long) p * n;
                String next = hasMore
                    ? java.util.Base64.getEncoder().encodeToString(
                        String.valueOf(p + 1).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8))
                    : null;
                return new CursorResultVo(vos, next, hasMore);
            });
    }

    private static FedItemVo toItemVo(FedItem item) {
        FedItem.FedItemSpec s = item.getSpec();
        FedItem.FedItemStatus st = item.getStatus();
        return new FedItemVo(
            item.getMetadata() == null ? null : item.getMetadata().getName(),
            s.getSiteName(), null, s.getKind(), s.getSourceName(),
            s.getTitle(), s.getExcerpt(), s.getHtml(),
            s.getAuthorName(), s.getAuthorDisplay(), s.getContentUrl(),
            s.getSourceCreatedAt(),
            st == null ? null : st.getFirstSeenAt());
    }
}
