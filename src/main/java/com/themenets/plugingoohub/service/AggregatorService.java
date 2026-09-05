package com.themenets.plugingoohub.service;

import com.themenets.plugingoohub.domain.vo.CursorResultVo;
import com.themenets.plugingoohub.domain.vo.FedItemVo;
import com.themenets.plugingoohub.domain.vo.SiteItemVo;
import reactor.core.publisher.Mono;

/**
 * 聚合器（阶段二核心）：逐站拉对端增量时间线入库 FedItem，并提供全网混排读端。
 * <p>
 * 同步语义（幂等可重入，与方案②消费方约定一致）：
 * <ul>
 *   <li>每站独立游标（FedSite.status.lastSyncCursor = 上次成功批次的最新 sourceCreatedAt）；</li>
 *   <li>items 按 (siteName, sourceName) upsert（同锚点覆盖，重复条目取对端最新）；</li>
 *   <li>tombstones 按 (siteName, sourceName) 找缓存条目置 status.deleted=true（软失效）；</li>
 *   <li>同步前刷 capabilities（元数据+计数），失败仅记 FedSite.status.lastError 不断流。</li>
 * </ul>
 */
public interface AggregatorService {

    /**
     * 单站聚合：capabilities 刷新元数据 + since 增量入库（墓碑置位）。
     *
     * @param siteName FedSite metadata.name
     * @return 同步后的站点目录条目（含刷新后的计数/错误状态）
     */
    Mono<SiteItemVo> syncSite(String siteName);

    /** 全站聚合：顺序同步（不并发轰对端），单站失败不中断整体。返回成功站点数 */
    Mono<Integer> syncAll();

    /**
     * 全网聚合时间线（公开口径）：未失效条目按 (sourceCreatedAt, name) 倒序混排。
     *
     * @param siteName 可选，只看某站（null/空=全部）
     * @param kind     可选，"note"/"topic"/null=全部
     * @param page     页码（1 起）
     * @param size     每页条数
     */
    Mono<CursorResultVo> listItems(String siteName, String kind, int page, int size);
}
