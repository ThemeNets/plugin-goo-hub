package com.themenets.plugingoohub;

import static com.themenets.plugingoohub.constants.HubRoutes.EXTENSION_GROUP;
import static com.themenets.plugingoohub.constants.HubRoutes.SITE_APPROVED;
import static com.themenets.plugingoohub.constants.HubRoutes.SITE_URL;

import com.themenets.plugingoohub.extension.FedSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 咕咕星系（plugin-goo-hub）入口：注册联邦站点模型与索引。
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
        log.info("plugin-goo-hub 启动完成（FedSite 已注册）");
    }

    @Override
    public void stop() {
        schemeManager.unregister(schemeManager.get(FedSite.class));
        log.info("plugin-goo-hub 已停止");
    }
}
