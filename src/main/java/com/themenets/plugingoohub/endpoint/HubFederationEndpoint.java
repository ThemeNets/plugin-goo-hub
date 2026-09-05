package com.themenets.plugingoohub.endpoint;

import static com.themenets.plugingoohub.constants.HubRoutes.API_VERSION;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_ITEMS;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_PAGE;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_REGISTER;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_SITES;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_SYNC;

import com.themenets.plugingoohub.domain.vo.CursorResultVo;
import com.themenets.plugingoohub.domain.vo.SiteItemVo;
import com.themenets.plugingoohub.service.AggregatorService;
import com.themenets.plugingoohub.service.RegistrationService;
import lombok.RequiredArgsConstructor;
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
 * </ul>
 * 审核开关 / 限流属治理项，后续版本按需加。
 */
@Component
@RequiredArgsConstructor
public class HubFederationEndpoint implements CustomEndpoint {

    private final RegistrationService registrationService;
    private final AggregatorService aggregatorService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST(FEDERATION_REGISTER, this::register)
            .GET(FEDERATION_SITES, this::sites)
            .POST(FEDERATION_SYNC, this::sync)
            .GET(FEDERATION_ITEMS, this::items)
            .GET(FEDERATION_PAGE, this::page)
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

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(API_VERSION);
    }
}
