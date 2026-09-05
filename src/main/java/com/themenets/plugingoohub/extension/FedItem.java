package com.themenets.plugingoohub.extension;

import static com.themenets.plugingoohub.constants.HubRoutes.EXTENSION_GROUP;
import static com.themenets.plugingoohub.constants.HubRoutes.VERSION;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 联邦聚合条目（对端公开内容的 hub 侧缓存）。
 * <p>
 * metadata.name = "feditem-" + (siteName + ":" + sourceName) 摘要 —— 同一条目重复
 * 同步幂等更新不重建。spec 全部字段入库前经 ContentSanitizer 消毒；
 * html 永远是对端 sanitize 产物的二次消毒结果，永不存 raw。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = EXTENSION_GROUP, version = VERSION, kind = "FedItem",
    plural = "feditems", singular = "feditem")
@Schema(description = "咕咕星系聚合条目（对端公开内容缓存）")
public class FedItem extends AbstractExtension {

    @Schema(description = "条目规格")
    private FedItemSpec spec;

    @Schema(description = "条目观测状态")
    private FedItemStatus status;

    @Data
    @Schema(description = "条目规格")
    public static class FedItemSpec {

        @Schema(description = "来源站点（FedSite metadata.name）",
            requiredMode = Schema.RequiredMode.REQUIRED)
        private String siteName;

        @Schema(description = "来源站点地址（拼卡片链接冗余）")
        private String siteUrl;

        @Schema(description = "类型：note | topic", requiredMode = Schema.RequiredMode.REQUIRED)
        private String kind;

        @Schema(description = "对端条目 name（站点内唯一）",
            requiredMode = Schema.RequiredMode.REQUIRED)
        private String sourceName;

        @Schema(description = "标题（Topic 标题；咕咕为摘要截断）")
        private String title;

        @Schema(description = "摘要（消毒后）")
        private String excerpt;

        @Schema(description = "消毒后全文")
        private String html;

        @Schema(description = "对端作者 username")
        private String authorName;

        @Schema(description = "对端作者展示名")
        private String authorDisplay;

        @Schema(description = "源站内容链接（跳转用）")
        private String contentUrl;

        @Schema(description = "对端创建时间（排序键之一）")
        private Instant sourceCreatedAt;
    }

    @Data
    @Schema(description = "条目观测状态")
    public static class FedItemStatus {

        @Schema(description = "墓碑失效（对端软删除后置 true，列表不再返回）")
        private Boolean deleted = false;

        @Schema(description = "失效时间")
        private Instant deletedAt;

        @Schema(description = "首次同步时间")
        private Instant firstSeenAt;
    }
}
