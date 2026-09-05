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
 * 用户级站点订阅（阶段四）：hub 站用户 → 联邦站点 的关注关系。
 * <p>
 * metadata.name = "fedfollow-" + (username + ":" + siteName) 摘要 —— 同一用户
 * 对同站重复关注幂等（不重建）。username 为 hub 站 User metadata.name；
 * siteName 为 FedSite metadata.name。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = EXTENSION_GROUP, version = VERSION, kind = "FedFollow",
    plural = "fedfollows", singular = "fedfollow")
@Schema(description = "咕咕星系用户订阅关系")
public class FedFollow extends AbstractExtension {

    @Schema(description = "订阅规格")
    private FedFollowSpec spec;

    @Data
    @Schema(description = "订阅规格")
    public static class FedFollowSpec {

        @Schema(description = "hub 站用户名（User metadata.name）",
            requiredMode = Schema.RequiredMode.REQUIRED)
        private String username;

        @Schema(description = "被订阅站点（FedSite metadata.name）",
            requiredMode = Schema.RequiredMode.REQUIRED)
        private String siteName;

        @Schema(description = "关注时间")
        private Instant createdAt;
    }
}
