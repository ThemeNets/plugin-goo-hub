package com.themenets.plugingoohub.constants;

/**
 * 咕咕星系（plugin-goo-hub）路径集中定义。
 * hub API group 与 Extension group 独立于 plugin-goo（两个插件各占自己的 group）。
 */
public final class HubRoutes {

    private HubRoutes() {}

    public static final String DOMAIN = "themenets.com";
    public static final String VERSION = "v1alpha1";

    /** Extension GVK group */
    public static final String EXTENSION_GROUP = "hub.goo." + DOMAIN;

    /** hub API group（登记/目录/聚合页端点） */
    public static final String API_GROUP = "hub.api.goo." + DOMAIN;
    public static final String API_VERSION = API_GROUP + "/" + VERSION;

    /** 站点自助登记（公开，任何人可为自己的站登记） */
    public static final String FEDERATION_REGISTER = "federation/register";
    /** 站点目录（公开列表） */
    public static final String FEDERATION_SITES = "federation/sites";

    /** Extension 索引字段名（与 FedSite 字段同步维护） */
    public static final String SITE_URL = "spec.siteUrl";
    public static final String SITE_APPROVED = "spec.approved";

    /** plugin-goo 公开 API 版本（拉对端 capabilities / since 用） */
    public static final String GOO_PUBLIC_API_VERSION = "api.goo." + DOMAIN + "/" + VERSION;
}
