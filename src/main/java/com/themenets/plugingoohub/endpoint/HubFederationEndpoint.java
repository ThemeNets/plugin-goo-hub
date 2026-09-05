package com.themenets.plugingoohub.endpoint;

import static com.themenets.plugingoohub.constants.HubRoutes.API_VERSION;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_FOLLOWS;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_FOLLOW;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_ITEMS;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_MY_ITEMS;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_PAGE;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_REGISTER;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_SITES;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_SYNC;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_UNFOLLOW;

import com.themenets.plugingoohub.domain.vo.CursorResultVo;
import com.themenets.plugingoohub.domain.vo.SiteItemVo;
import com.themenets.plugingoohub.service.AggregatorService;
import com.themenets.plugingoohub.service.FollowService;
import com.themenets.plugingoohub.service.RegistrationService;
import com.themenets.plugingoohub.service.impl.FollowServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 咕咕星系 hub 端点：
 * <ul>
 *   <li>POST federation/register — 站点自助登记（阶段一）：body {@code {"siteUrl": "https://..."}}。
 *       服务端拉对端 {@code /federation/capabilities} 自动验证（不可达/不兼容拒绝），
 *       同 URL 重复登记幂等更新。</li>
 *   <li>GET federation/sites — 公开站点目录（阶段一）。</li>
 *   <li>POST federation/sync — 触发聚合（阶段二）：query 可选 siteName（缺省=全部已收录站）；
 *       站点自助触发，同步幂等可重入。</li>
 *   <li>GET federation/items — 全网聚合时间线（阶段二，公开）：
 *       query 可选 siteName/kind/page/size，(sourceCreatedAt, name) 倒序混排。</li>
 *   <li>GET federation/page — 「咕咕星系」统一页面（阶段三，公开，text/html）：
 *       站点目录 Tab + 全网时间线 Tab，数据由页面 JS 拉取上述 JSON。</li>
 *   <li>阶段四 用户订阅（登录）：GET federation/follows（我的关注）/ POST federation/follow /
 *       POST federation/unfollow（body {"siteName": ...}）/ GET federation/my-items
 *       （按我的关注过滤的全网时间线）。匿名一律 401。</li>
 * </ul>
 * 审核开关 / 限流属治理项，后续版本按需加。
 */
@Component
@RequiredArgsConstructor
public class HubFederationEndpoint implements CustomEndpoint {

    private final RegistrationService registrationService;
    private final AggregatorService aggregatorService;
    private final FollowService followService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST(FEDERATION_REGISTER, this::register)
            .GET(FEDERATION_SITES, this::sites)
            .POST(FEDERATION_SYNC, this::sync)
            .GET(FEDERATION_ITEMS, this::items)
            .GET(FEDERATION_PAGE, this::page)
            .GET(FEDERATION_FOLLOWS, this::follows)
            .POST(FEDERATION_FOLLOW, this::follow)
            .POST(FEDERATION_UNFOLLOW, this::unfollow)
            .GET(FEDERATION_MY_ITEMS, this::myItems)
            .build();
    }

    /** 站点自助登记：body {"siteUrl": "https://..."}，服务端拉对端 capabilities 自动验证 */
    private Mono<ServerResponse> register(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                Object siteUrl = body == null ? null : body.get("siteUrl");
                if (!(siteUrl instanceof String s) || s.isBlank()) {
                    return Mono.error(new IllegalArgumentException("siteUrl 必填"));
                }
                String submitter = body.get("submitter") instanceof String s2 ? s2 : "";
                return registrationService.register(s, submitter);
            })
            .switchIfEmpty(Mono.error(new IllegalArgumentException("siteUrl 必填")))
            .flatMap(vo -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(vo))
            .onErrorResume(HubEndpointSupport::mapError);
    }

    /** 公开站点目录（统一页面/各站「网络」区块数据源） */
    private Mono<ServerResponse> sites(ServerRequest request) {
        return registrationService.listSites()
            .flatMap((List<SiteItemVo> items) -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(items))
            .onErrorResume(HubEndpointSupport::mapError);
    }

    /** 触发聚合：query 可选 siteName（缺省=全部已收录站），同步幂等可重入 */
    private Mono<ServerResponse> sync(ServerRequest request) {
        String siteName = request.queryParam("siteName").orElse("");
        Mono<?> mono = siteName.isBlank()
            ? aggregatorService.syncAll()
            : aggregatorService.syncSite(siteName);
        return mono.flatMap(vo -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(vo))
            .onErrorResume(HubEndpointSupport::mapError);
    }

    /** 全网聚合条目：query 可选 siteName/kind/page/size，(sourceCreatedAt, name) 倒序混排 */
    private Mono<ServerResponse> items(ServerRequest request) {
        String siteName = request.queryParam("siteName").orElse("");
        String kind = request.queryParam("kind").orElse("");
        int page = parseInt(request.queryParam("page").orElse(""), 1);
        int size = parseInt(request.queryParam("size").orElse("30"), 30);
        return aggregatorService.listItems(siteName, kind, page, size)
            .flatMap(vo -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(vo))
            .onErrorResume(HubEndpointSupport::mapError);
    }

    private static int parseInt(String raw, int defVal) {
        try {
            return raw.isBlank() ? defVal : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defVal;
        }
    }

    /** 「咕咕星系」统一页面（阶段三）：纯静态壳，数据由页面 JS 拉取 sites/items JSON */
    private Mono<ServerResponse> page(ServerRequest request) {
        byte[] html = GalaxyPageBuilder.build().getBytes(StandardCharsets.UTF_8);
        return ServerResponse.ok()
            .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
            .bodyValue(html);
    }

    // ---------- 阶段四：用户订阅（登录） ----------

    /** 匿名 → 401 响应 */
    private static Mono<ServerResponse> unauthorized() {
        return ServerResponse.status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", "请先登录咕咕总站"));
    }

    /** 我关注的站点名列表（登录） */
    private Mono<ServerResponse> follows(ServerRequest request) {
        return FollowServiceImpl.currentUser()
            .flatMap(u -> followService.listFollows(u)
                .flatMap(names -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(names)))
            .switchIfEmpty(unauthorized())
            .onErrorResume(HubEndpointSupport::mapError);
    }

    /** 关注站点：body {"siteName": "fed-xxx"}（登录） */
    private Mono<ServerResponse> follow(ServerRequest request) {
        return FollowServiceImpl.currentUser()
            .flatMap(u -> request.bodyToMono(Map.class).defaultIfEmpty(Map.of())
                .flatMap(body -> {
                    String siteName = body.get("siteName") instanceof String s ? s : "";
                    if (siteName.isBlank()) {
                        return Mono.error(new IllegalArgumentException("siteName 必填"));
                    }
                    return followService.follow(u, siteName)
                        .then(ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("ok", true)));
                }))
            .switchIfEmpty(unauthorized())
            .onErrorResume(HubEndpointSupport::mapError);
    }

    /** 取消关注：body {"siteName": "fed-xxx"}（登录） */
    private Mono<ServerResponse> unfollow(ServerRequest request) {
        return FollowServiceImpl.currentUser()
            .flatMap(u -> request.bodyToMono(Map.class).defaultIfEmpty(Map.of())
                .flatMap(body -> {
                    String siteName = body.get("siteName") instanceof String s ? s : "";
                    if (siteName.isBlank()) {
                        return Mono.error(new IllegalArgumentException("siteName 必填"));
                    }
                    return followService.unfollow(u, siteName)
                        .then(ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("ok", true)));
                }))
            .switchIfEmpty(unauthorized())
            .onErrorResume(HubEndpointSupport::mapError);
    }

    /** 我的订阅时间线：登录用户的关注站点聚合条目（页码模式） */
    private Mono<ServerResponse> myItems(ServerRequest request) {
        return FollowServiceImpl.currentUser()
            .flatMap(u -> followService.listFollows(u)
                .flatMap(names -> {
                    String kind = request.queryParam("kind").orElse("");
                    int page = parseInt(request.queryParam("page").orElse(""), 1);
                    int size = parseInt(request.queryParam("size").orElse("30"), 30);
                    return aggregatorService.listItemsBySites(names, kind, page, size);
                })
                .flatMap(vo -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(vo)))
            .switchIfEmpty(unauthorized())
            .onErrorResume(HubEndpointSupport::mapError);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(API_VERSION);
    }
}
