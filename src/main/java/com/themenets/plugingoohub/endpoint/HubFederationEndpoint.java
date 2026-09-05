package com.themenets.plugingoohub.endpoint;

import static com.themenets.plugingoohub.constants.HubRoutes.API_VERSION;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_REGISTER;
import static com.themenets.plugingoohub.constants.HubRoutes.FEDERATION_SITES;

import com.themenets.plugingoohub.domain.vo.SiteItemVo;
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

import java.util.List;
import java.util.Map;

/**
 * 咕咕星系 hub 端点（阶段一：站点登记 + 公开目录）：
 * <ul>
 *   <li>POST federation/register — 站点自助登记：body {@code {"siteUrl": "https://..."}}。
 *       服务端拉对端 {@code /federation/capabilities} 自动验证（不可达/不兼容拒绝），
 *       同 URL 重复登记幂等更新。</li>
 *   <li>GET federation/sites — 公开站点目录（统一页面/各站「网络」区块数据源）。</li>
 * </ul>
 * 审核开关 / 限流属治理项，后续版本按需加。
 */
@Component
@RequiredArgsConstructor
public class HubFederationEndpoint implements CustomEndpoint {

    private final RegistrationService registrationService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST(FEDERATION_REGISTER, this::register)
            .GET(FEDERATION_SITES, this::sites)
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

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(API_VERSION);
    }
}
