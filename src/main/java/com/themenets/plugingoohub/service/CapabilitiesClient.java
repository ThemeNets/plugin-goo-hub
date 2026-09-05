package com.themenets.plugingoohub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 对端 capabilities 拉取器（JDK HttpClient，与 plugin-goo 的 HTTP 惯例一致，
 * 避免把第二套 HTTP/Netty 栈塞进插件 classloader）。
 * <p>
 * 拉取 {@code {siteUrl}/apis/api.goo.themenets.com/v1alpha1/federation/capabilities}
 * 并解析为 {@link CapabilitiesVo}；登记与聚合共用。
 */
public class CapabilitiesClient {

    /** 对端 capabilities 解析结果（hub 侧扁平结构；解析容错：缺字段取默认值） */
    public record CapabilitiesVo(
        int feedVersion, List<String> kinds,
        String title, String subtitle, String description, String siteUrl,
        String routePrefix, long noteCount, long topicCount) {

        public static final CapabilitiesVo EMPTY =
            new CapabilitiesVo(0, List.of(), "", "", "", "", "", 0, 0);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int CONNECT_TIMEOUT_S = 5;
    private static final int REQUEST_TIMEOUT_S = 10;
    private static final int TOTAL_TIMEOUT_S = 15;

    /**
     * 拉取对端 capabilities 并解析（超时/非 200/解析失败都转 error，
     * 上层据此拒绝登记或标记同步失败）。
     */
    public Mono<CapabilitiesVo> fetch(String siteUrl) {
        String url = siteUrl + "/apis/" + com.themenets.plugingoohub.constants.HubRoutes.GOO_PUBLIC_API_VERSION
            + "/federation/capabilities";
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET().build();
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("站点地址非法: " + siteUrl));
        }
        return Mono.fromFuture(() -> HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()))
            .timeout(Duration.ofSeconds(15))
            .flatMap(resp -> {
                if (resp.statusCode() != 200) {
                    return Mono.error(new IllegalStateException(
                        "目标站 capabilities 返回 HTTP " + resp.statusCode()));
                }
                try {
                    return Mono.just(parse(resp.body()));
                } catch (Exception e) {
                    return Mono.error(new IllegalStateException("对端 capabilities 解析失败"));
                }
            });
    }

    /** 解析 capabilities JSON（容错：缺字段取默认值；feedVersion 缺失按 0=不兼容处理） */
    public static CapabilitiesVo parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            int feedVersion = root.path("feedVersion").asInt(0);
            List<String> kinds = new ArrayList<>();
            root.path("kinds").forEach(k -> kinds.add(k.asText()));
            JsonNode site = root.path("site");
            return new CapabilitiesVo(
                feedVersion, kinds,
                site.path("title").asText(""),
                site.path("subtitle").asText(""),
                site.path("description").asText(""),
                site.path("url").asText(""),
                root.path("routePrefix").asText(""),
                root.path("noteCount").asLong(0),
                root.path("topicCount").asLong(0));
        } catch (Exception e) {
            return CapabilitiesVo.EMPTY;
        }
    }
}
