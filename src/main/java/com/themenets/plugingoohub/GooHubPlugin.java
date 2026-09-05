package com.themenets.plugingoohub;

import static com.themenets.plugingoohub.constants.HubRoutes.EXTENSION_GROUP;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_DELETED;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_KIND;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_SITE_NAME;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_SOURCE_CREATED_AT;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_SOURCE_NAME;
import static com.themenets.plugingoohub.constants.HubRoutes.SITE_APPROVED;
import static com.themenets.plugingoohub.constants.HubRoutes.SITE_URL;

import com.themenets.plugingoohub.extension.FedItem;
import com.themenets.plugingoohub.extension.FedSite;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 咕咕星系（plugin-goo-hub）入口：注册联邦站点/聚合条目模型与索引。
 * <p>
 * hub 装在咕咕总站，消费 plugin-goo 的公开地基
 * （federation/capabilities、timelines/local?since=、feed.atom），
 * 提供：站点注册表 → 聚合器 → 统一页面 → 用户订阅。
 */
@Component
public class GooHubPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(GooHubPlugin.class);

    private final SchemeManager schemeManager;

    public GooHubPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(FedSite.class, specs -> {
            specs.add(IndexSpecs.<FedSite, String>single(SITE_URL, String.class)
                .indexFunc(site -> site.getSpec() == null
                    ? null : site.getSpec().getSiteUrl()));
            specs.add(IndexSpecs.<FedSite, String>single(SITE_APPROVED, String.class)
                .indexFunc(site -> site.getSpec() == null
                    || site.getSpec().getApproved() == null
                    ? "false" : (site.getSpec().getApproved() ? "true" : "false")));
        });
        schemeManager.register(FedItem.class, specs -> {
            specs.add(IndexSpecs.<FedItem, String>single(ITEM_SITE_NAME, String.class)
                .indexFunc(item -> item.getSpec() == null
                    ? null : item.getSpec().getSiteName()));
            specs.add(IndexSpecs.<FedItem, String>single(ITEM_SOURCE_NAME, String.class)
                .indexFunc(item -> item.getSpec() == null
                    ? null : item.getSpec().getSourceName()));
            specs.add(IndexSpecs.<FedItem, String>single(ITEM_KIND, String.class)
                .indexFunc(item -> item.getSpec() == null
                    ? null : item.getSpec().getKind()));
            specs.add(IndexSpecs.<FedItem, Instant>single(ITEM_SOURCE_CREATED_AT, Instant.class)
                .indexFunc(item -> item.getSpec() == null
                    ? null : item.getSpec().getSourceCreatedAt()));
            specs.add(IndexSpecs.<FedItem, String>single(ITEM_DELETED, String.class)
                .indexFunc(item -> {
                    if (item.getStatus() == null || item.getStatus().getDeleted() == null
                        || !item.getStatus().getDeleted()) {
                        return "false";
                    }
                    return "true";
                }));
        });
        log.info("plugin-goo-hub 启动完成（FedSite/FedItem 已注册）");
    }

    @Override
    public void stop() {
        schemeManager.unregister(schemeManager.get(FedSite.class));
        schemeManager.unregister(schemeManager.get(FedItem.class));
        log.info("plugin-goo-hub 已停止");
    }
}
