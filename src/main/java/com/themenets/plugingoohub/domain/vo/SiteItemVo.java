package com.themenets.plugingoohub.domain.vo;

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
}
