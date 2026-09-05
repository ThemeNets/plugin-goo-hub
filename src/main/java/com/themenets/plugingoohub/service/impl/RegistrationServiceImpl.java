package com.themenets.plugingoohub.service.impl;

import com.themenets.plugingoohub.domain.vo.SiteItemVo;
import com.themenets.plugingoohub.extension.FedSite;
import com.themenets.plugingoohub.service.CapabilitiesClient;
import com.themenets.plugingoohub.service.RegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import static run.halo.app.extension.index.query.Queries.equal;

/**
 * 站点登记实现（阶段一）：登记 → 拉 capabilities 自动验证 → 落 FedSite。
 * <p>
 * 同 URL 重复登记幂等更新 status（不重建条目）；feedVersion != 1 或不可达均拒绝。
 */
@Service
@RequiredArgsConstructor
public class RegistrationServiceImpl implements RegistrationService {

    private final ReactiveExtensionClient client;
    private final CapabilitiesClient capClient;

    /** 规范化站点地址：补 scheme、去尾斜杠、host 小写 */
    static String normalizeSiteUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("siteUrl 必填");
        }
        String url = rawUrl.trim();
        if (!url.matches("(?i)^https?://.+")) {
            url = "https://" + url;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("siteUrl 非法: " + rawUrl);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("siteUrl 缺少主机名: " + rawUrl);
        }
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase();
        int port = uri.getPort();
        String hostPart = host.toLowerCase(Locale.ROOT)
            + (port == -1 || (uri.getScheme().equalsIgnoreCase("https") && port == 443)
            || (scheme.equals("http") && port == 80) ? "" : ":" + port);
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        if ("/".equals(path)) {
            path = "";
        }
        return scheme + "://" + hostPart + path;
    }

    /** FedSite metadata.name：fed- + siteUrl 摘要（幂等登记锚点） */
    static String siteName(String siteUrl) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(siteUrl.toLowerCase(Locale.ROOT)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "fed-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return "fed-" + Integer.toHexString(siteUrl.hashCode());
        }
    }

    @Override
    public Mono<SiteItemVo> register(String rawUrl, String submitter) {
        // normalizeSiteUrl 可能同步抛 IllegalArgumentException → 包装为 Mono.error
        return Mono.fromCallable(() -> normalizeSiteUrl(rawUrl))
            .flatMap(siteUrl -> capClient.fetch(siteUrl)
                .flatMap(cap -> {
                    if (cap.feedVersion() != 1) {
                        return Mono.error(new IllegalStateException(
                            "对端 feedVersion=" + cap.feedVersion()
                                + "，本端仅支持 1（请升级对端 plugin-goo）"));
                    }
                    if (cap.kinds().isEmpty()) {
                        return Mono.error(new IllegalStateException(
                            "对端 capabilities 未声明任何内容类型"));
                    }
                    String name = siteName(siteUrl);
                    return client.fetch(FedSite.class, name)
                        .flatMap(existing -> updateSite(existing, siteUrl, submitter, cap))
                        .switchIfEmpty(Mono.defer(() -> createSite(name, siteUrl, submitter, cap)))
                        .thenReturn(toVo(name, siteUrl, cap, Instant.now()));
                }));
    }

    private Mono<FedSite> createSite(String name, String siteUrl, String submitter,
                                     CapabilitiesClient.CapabilitiesVo cap) {
        FedSite site = new FedSite();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        site.setMetadata(metadata);
        FedSite.FedSiteSpec spec = new FedSite.FedSiteSpec();
        spec.setSiteUrl(siteUrl);
        spec.setSubmitter(submitter == null ? "" : submitter);
        spec.setApproved(true);
        site.setSpec(spec);
        site.setStatus(new FedSite.FedSiteStatus());
        applyCap(site, cap);
        return client.create(site);
    }

    private Mono<FedSite> updateSite(FedSite existing, String siteUrl, String submitter,
                                     CapabilitiesClient.CapabilitiesVo cap) {
        FedSite.FedSiteSpec spec = existing.getSpec();
        if (spec == null) {
            spec = new FedSite.FedSiteSpec();
            existing.setSpec(spec);
        }
        spec.setSiteUrl(siteUrl);
        if (submitter != null && !submitter.isBlank()) {
            spec.setSubmitter(submitter);
        }
        if (spec.getApproved() == null) {
            spec.setApproved(true);
        }
        applyCap(existing, cap);
        return client.update(existing);
    }

    /** capabilities → FedSiteStatus 观测字段（幂等刷新） */
    private static void applyCap(FedSite site, CapabilitiesClient.CapabilitiesVo cap) {
        FedSite.FedSiteStatus status = site.getStatus();
        if (status == null) {
            status = new FedSite.FedSiteStatus();
            site.setStatus(status);
        }
        status.setTitle(cap.title());
        status.setSubtitle(cap.subtitle());
        status.setDescription(cap.description());
        // 站点 URL 优先 capabilities 自报 external-url（与访客看到的前台同源），缺省回退登记地址
        status.setSiteUrl(cap.siteUrl() == null || cap.siteUrl().isBlank()
            ? site.getSpec().getSiteUrl() : cap.siteUrl());
        status.setRoutePrefix(cap.routePrefix());
        status.setFeedVersion(cap.feedVersion());
        status.setKinds(cap.kinds());
        status.setNoteCount(cap.noteCount());
        status.setTopicCount(cap.topicCount());
        status.setLastSyncAt(Instant.now());
        status.setLastError(null);
    }

    private static SiteItemVo toVo(String name, String siteUrl,
                                   CapabilitiesClient.CapabilitiesVo cap, Instant now) {
        return new SiteItemVo(
            name,
            cap.title(),
            cap.subtitle(),
            cap.description(),
            cap.siteUrl() == null || cap.siteUrl().isBlank() ? siteUrl : cap.siteUrl(),
            cap.routePrefix(),
            cap.feedVersion(),
            cap.kinds(),
            cap.noteCount(),
            cap.topicCount(),
            now,
            null);
    }

    @Override
    public Mono<List<SiteItemVo>> listSites() {
        return client.listBy(FedSite.class,
                ListOptions.builder()
                    .fieldQuery(equal("spec.approved", "true"))
                    .build(),
                run.halo.app.extension.PageRequestImpl.of(1, 1000,
                    org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.asc("status.title"))))
            .map(result -> {
                List<SiteItemVo> items = new ArrayList<>();
                for (FedSite site : result.getItems()) {
                    // 双保险：索引已过滤 approved，这里再兜底（防索引语义漂移 + 可单测）
                    if (site.getSpec() == null || !Boolean.TRUE.equals(site.getSpec().getApproved())) {
                        continue;
                    }
                    items.add(toItemVo(site));
                }
                items.sort(Comparator.comparing(
                    s -> s.title() == null ? "" : s.title().toLowerCase()));
                return items;
            });
    }

    private static SiteItemVo toItemVo(FedSite site) {
        FedSite.FedSiteSpec spec = site.getSpec();
        FedSite.FedSiteStatus status = site.getStatus();
        String siteUrl = spec == null ? null : spec.getSiteUrl();
        String url = status == null || status.getSiteUrl() == null
            || status.getSiteUrl().isBlank() ? siteUrl : status.getSiteUrl();
        return new SiteItemVo(
            site.getMetadata() == null ? null : site.getMetadata().getName(),
            status == null ? null : status.getTitle(),
            status == null ? null : status.getSubtitle(),
            status == null ? null : status.getDescription(),
            url,
            status == null ? null : status.getRoutePrefix(),
            status == null ? null : status.getFeedVersion(),
            status == null ? null : status.getKinds(),
            status == null ? null : status.getNoteCount(),
            status == null ? null : status.getTopicCount(),
            status == null ? null : status.getLastSyncAt(),
            status == null ? null : status.getLastError());
    }
}
