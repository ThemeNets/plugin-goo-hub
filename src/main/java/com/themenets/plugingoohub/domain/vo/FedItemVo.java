package com.themenets.plugingoohub.domain.vo;

import java.time.Instant;

/**
 * 聚合条目 VO（公开，全网时间线/统一页面共用）。
 * url 为源站内容链接（跳转用）；siteTitle 为来源站点名。
 */
public record FedItemVo(
    String name, String siteName, String siteTitle, String kind,
    String sourceName, String title, String excerpt, String html,
    String authorName, String authorDisplay, String contentUrl,
    Instant sourceCreatedAt, Instant firstSeenAt) {
}
