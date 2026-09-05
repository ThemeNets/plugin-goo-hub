package com.themenets.plugingoohub.endpoint;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * hub 端点共用错误映射（400/404/502，消息纯文本）。
 * 与 plugin-goo 的 EndpointSupport 同构但独立维护（两个插件不共享类）。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class HubEndpointSupport {

    static Mono<ServerResponse> mapError(Throwable error) {
        if (error instanceof IllegalArgumentException argument) {
            return ServerResponse.badRequest().bodyValue(argument.getMessage());
        }
        if (error instanceof IllegalStateException state) {
            // 目标站不可达 / capabilities 不兼容 → 502（登记语义：问题在对端，不在请求格式）
            return ServerResponse.status(HttpStatus.BAD_GATEWAY)
                .bodyValue(state.getMessage());
        }
        if (error instanceof java.util.NoSuchElementException) {
            return ServerResponse.status(HttpStatus.NOT_FOUND).bodyValue("站点不存在");
        }
        return Mono.error(error);
    }
}
