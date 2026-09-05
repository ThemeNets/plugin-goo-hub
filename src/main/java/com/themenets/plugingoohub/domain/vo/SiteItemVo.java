package com.themenets.plugingoohub.domain.vo;

import com.themenets.plugingoohub.extension.FedSite;

import java.time.Instant;
import java.util.List;

/**
 * 站点目录条目（公开，统一页面/各站「网络」区块共用）。
 * url 优先取对端 capabilities 自报的 external-url，缺省回退登记地址。
 */
public record SiteItemVo(
    String name, String title, String subtitle, String description, String url,
    String routePrefix, Integer feedVersion, List<String> kinds,
    Long noteCount, Long topicCount, Instant lastSyncAt, String lastError) {

    /** 从 FedSite Extension 构造（null 安全；供登记/聚合两处复用） */
    public static SiteItemVo from(FedSite site) {
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
