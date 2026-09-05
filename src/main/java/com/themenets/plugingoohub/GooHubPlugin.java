package com.themenets.plugingoohub;

import static com.themenets.plugingoohub.constants.HubRoutes.EXTENSION_GROUP;
import static com.themenets.plugingoohub.constants.HubRoutes.FOLLOW_SITE;
import static com.themenets.plugingoohub.constants.HubRoutes.FOLLOW_USER;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_DELETED;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_KIND;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_SITE_NAME;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_SOURCE_CREATED_AT;
import static com.themenets.plugingoohub.constants.HubRoutes.ITEM_SOURCE_NAME;
import static com.themenets.plugingoohub.constants.HubRoutes.SITE_APPROVED;
import static com.themenets.plugingoohub.constants.HubRoutes.SITE_URL;

import com.themenets.plugingoohub.extension.FedFollow;
import com.themenets.plugingoohub.extension.FedItem;
import com.themenets.plugingoohub.extension.FedSite;
import com.themenets.plugingoohub.service.AggregatorService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 咕咕星系（plugin-goo-hub）入口：注册联邦站点/聚合条目模型与索引 + 定时聚合线程。
 * <p>
 * hub 装在咕咕总站，消费 plugin-goo 的公开地基
 * （federation/capabilities、timelines/local?since=、feed.atom），
 * 提供：站点注册表 → 聚合器 → 统一页面 → 用户订阅。
 * <p>
 * 定时聚合：daemon 线程启动时立即 syncAll() 铺底，之后每 {@value #SYNC_INTERVAL_MINUTES}
 * 分钟一轮；单站失败仅记 lastError 不断轮。
 */
@Component
public class GooHubPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(GooHubPlugin.class);
    /** 定时聚合间隔（分钟） */
    private static final long SYNC_INTERVAL_MINUTES = 10;

    private final SchemeManager schemeManager;
    private final AggregatorService aggregatorService;
    private Thread syncThread;

    public GooHubPlugin(PluginContext pluginContext, SchemeManager schemeManager,
                        AggregatorService aggregatorService) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.aggregatorService = aggregatorService;
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
        schemeManager.register(FedFollow.class, specs -> {
            specs.add(IndexSpecs.<FedFollow, String>single(FOLLOW_USER, String.class)
                .indexFunc(f -> f.getSpec() == null
                    ? null : f.getSpec().getUsername()));
            specs.add(IndexSpecs.<FedFollow, String>single(FOLLOW_SITE, String.class)
                .indexFunc(f -> f.getSpec() == null
                    ? null : f.getSpec().getSiteName()));
        });
        log.info("plugin-goo-hub 启动完成（FedSite/FedItem/FedFollow 已注册）");

        // 定时聚合线程：首轮立即铺底（since=null 拉各站最新一批），之后每 10 分钟增量一轮。
        // daemon 线程 + stop() 中断；单站失败仅记 lastError（syncAll 内部吞掉）不断轮。
        syncThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    aggregatorService.syncAll()
                        .timeout(Duration.ofMinutes(8))
                        .block(Duration.ofMinutes(9));
                } catch (Exception e) {
                    log.warn("定时聚合异常 — {}", e.toString());
                }
                try {
                    Thread.sleep(SYNC_INTERVAL_MINUTES * 60_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "goo-hub-sync");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    @Override
    public void stop() {
        if (syncThread != null) {
            syncThread.interrupt();
            syncThread = null;
        }
        schemeManager.unregister(schemeManager.get(FedSite.class));
        schemeManager.unregister(schemeManager.get(FedItem.class));
        schemeManager.unregister(schemeManager.get(FedFollow.class));
        log.info("plugin-goo-hub 已停止");
    }
}
