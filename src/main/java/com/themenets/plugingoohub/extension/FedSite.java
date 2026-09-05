package com.themenets.plugingoohub.extension;

import static com.themenets.plugingoohub.constants.HubRoutes.EXTENSION_GROUP;
import static com.themenets.plugingoohub.constants.HubRoutes.VERSION;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 联邦站点（咕咕星系注册表条目）。
 * <p>
 * spec 是登记侧输入（siteUrl + 提交人），status 是聚合器从对端
 * {@code /federation/capabilities} 刷新的观测数据（站点元数据 + 最近同步状态）。
 * metadata.name = "fed-" + siteUrl 摘要，同 URL 重复登记幂等更新不重建。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = EXTENSION_GROUP, version = VERSION, kind = "FedSite",
    plural = "fedsites", singular = "fedsite")
@Schema(description = "咕咕星系站点注册表条目")
public class FedSite extends AbstractExtension {

    @Schema(description = "站点规格")
    private FedSiteSpec spec;

    @Schema(description = "站点观测状态（聚合器从对端 capabilities 刷新）")
    private FedSiteStatus status;

    @Data
    @Schema(description = "站点规格")
    public static class FedSiteSpec {

        @Schema(description = "站点根地址（https://host，规范化去尾斜杠）",
            requiredMode = Schema.RequiredMode.REQUIRED)
        private String siteUrl;

        @Schema(description = "登记人（Halo user，匿名自助登记为空）")
        private String submitter;

        @Schema(description = "收录状态（true=已收录；审核开关后续版本加，当前登记即收录）")
        private Boolean approved = true;
    }

    @Data
    @Schema(description = "站点观测状态")
    public static class FedSiteStatus {

        @Schema(description = "站点名称（对端 capabilities.site.title）")
        private String title;

        @Schema(description = "站点副标题")
        private String subtitle;

        @Schema(description = "站点简介")
        private String description;

        @Schema(description = "站点对外地址（对端 capabilities.site.url）")
        private String siteUrl;

        @Schema(description = "对端 goo 前台路由前缀（拼内容链接用）")
        private String routePrefix;

        @Schema(description = "对端 feedVersion")
        private Integer feedVersion;

        @Schema(description = "对端公开内容类型")
        private List<String> kinds;

        @Schema(description = "对端公开咕咕数")
        private Long noteCount;

        @Schema(description = "对端公开话题数")
        private Long topicCount;

        @Schema(description = "最近一次聚合/校验时间")
        private Instant lastSyncAt;

        @Schema(description = "最近一次同步错误（空=正常）")
        private String lastError;

        @Schema(description = "增量同步游标（上次成功拉取到的最新条目创建时间）")
        private Instant lastSyncCursor;
    }
}
