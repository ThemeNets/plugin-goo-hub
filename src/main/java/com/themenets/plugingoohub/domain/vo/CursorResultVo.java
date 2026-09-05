package com.themenets.plugingoohub.domain.vo;

import java.util.List;

/** 游标分页结果（hub 侧，与 plugin-goo CursorResult 契约同构） */
public record CursorResultVo(List<FedItemVo> items, String nextCursor, boolean hasMore) {
}
