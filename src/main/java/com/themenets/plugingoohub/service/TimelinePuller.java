package com.themenets.plugingoohub.service;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * 对端增量时间线拉取器（方案②"轻通知+拉取"的拉取侧抽象）。
 * <p>
 * 拉取 {@code {siteUrl}/apis/api.goo.themenets.com/v1alpha1/timelines/local?since=...}
 * 并解析为扁平 {@link PulledDelta}；聚合器只依赖本接口（测试可 mock）。
 */
public interface TimelinePuller {

    /**
     * 拉取对端增量（公开口径，游客视角）。
     *
     * @param siteUrl     对端站点根地址（已规范化）
     * @param routePrefix 对端前台路由前缀（拼内容链接用；空/缺省按 /goo）
     * @param since       增量游标（null 时从最新一批开始，用于铺底）
     * @param size        单批条目上限（对端 <=50）
     */
    Mono<PulledDelta> pullSince(String siteUrl, String routePrefix,
                                Instant since, int size);

    /** 拉取对端最新一批条目（铺底用，等价 since=null） */
    Mono<PulledDelta> pullLatest(String siteUrl, String routePrefix, int size);

    /** 对端墓碑（软删除通知） */
    record PulledTombstone(String name, String kind, Instant deletedAt) {
    }

    /** 对端增量条目（扁平投影，contentUrl 已拼好源站链接） */
    record PulledItem(
        String kind, String sourceName, String title, String excerpt, String html,
        String authorName, String authorDisplay, String contentUrl, Instant createdAt) {
    }

    /** 对端增量结果（条目 + 墓碑） */
    record PulledDelta(List<PulledItem> items, List<PulledTombstone> tombstones) {

        public static final PulledDelta EMPTY =
            new PulledDelta(List.of(), List.of());
    }
}
