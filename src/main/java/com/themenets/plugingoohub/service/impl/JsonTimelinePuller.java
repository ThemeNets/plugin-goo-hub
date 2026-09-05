package com.themenets.plugingoohub.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.themenets.plugingoohub.constants.HubRoutes;
import com.themenets.plugingoohub.service.TimelinePuller;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 对端增量时间线拉取器（JDK HttpClient + Jackson，零新依赖，与登记侧 HTTP 惯例一致）。
 * <p>
 * 解析对端 {@code GET timelines/local?since=} 的 TimelineDelta JSON：
 * {@code {"items":[{kind, note|topic, ...}], "tombstones":[{name, kind, deletedAt}]}}，
 * note/topic 嵌套结构投影为扁平 {@link PulledItem}（contentUrl 已拼好源站链接）。
 */
@Component
public class JsonTimelinePuller implements TimelinePuller {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** 对端缺省路由前缀（与 plugin-goo GooSettings.DEFAULT_ROUTE_PREFIX 一致） */
    private static final String DEFAULT_ROUTE_PREFIX = "/goo";

    @Override
    public Mono<PulledDelta> pullSince(String siteUrl, String routePrefix,
                                       Instant since, int size) {
        StringBuilder url = new StringBuilder(siteUrl)
            .append("/apis/").append(HubRoutes.GOO_PUBLIC_API_VERSION)
            .append("/timelines/local?size=").append(Math.max(1, Math.min(size, 50)))
            .append("&kind=all");
        if (since != null) {
            url.append("&since=").append(urlEncode(since.toString()));
        }
        return fetchDelta(url.toString(), siteUrl, routePrefix);
    }

    @Override
    public Mono<PulledDelta> pullLatest(String siteUrl, String routePrefix, int size) {
        return pullSince(siteUrl, routePrefix, null, size);
    }

    private Mono<PulledDelta> fetchDelta(String url, String siteUrl, String routePrefix) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET().build();
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("站点地址非法: " + url));
        }
        return Mono.fromFuture(() -> HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()))
            .timeout(Duration.ofSeconds(25))
            .flatMap(resp -> {
                if (resp.statusCode() != 200) {
                    return Mono.error(new IllegalStateException(
                        "对端 since 增量返回 HTTP " + resp.statusCode()));
                }
                try {
                    return Mono.just(parseDelta(resp.body(), siteUrl, routePrefix));
                } catch (Exception e) {
                    return Mono.error(new IllegalStateException("对端增量响应解析失败"));
                }
            });
    }

    /**
     * 解析对端 TimelineDelta JSON：
     * {@code {"items":[{kind, note|topic, ...}], "tombstones":[{name, kind, deletedAt}]}}。
     * note/topic 嵌套结构投影为扁平 PulledItem；解析失败整体报错（上层标记同步失败）。
     */
    public static PulledDelta parseDelta(String json, String siteUrl, String routePrefix) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String origin = siteOrigin(siteUrl);
            String prefix = routePrefix == null || routePrefix.isBlank()
                ? DEFAULT_ROUTE_PREFIX : routePrefix;
            List<PulledItem> items = new ArrayList<>();
            JsonNode itemsNode = root.path("items");
            if (itemsNode.isArray()) {
                for (JsonNode node : itemsNode) {
                    PulledItem item = parseItem(node, origin, prefix);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            List<PulledTombstone> tombstones = new ArrayList<>();
            JsonNode tbNode = root.path("tombstones");
            if (tbNode.isArray()) {
                for (JsonNode tb : tbNode) {
                    Instant deletedAt = parseInstant(tb.path("deletedAt").asText(null));
                    tombstones.add(new PulledTombstone(
                        tb.path("name").asText(""), tb.path("kind").asText("note"), deletedAt));
                }
            }
            return new PulledDelta(List.copyOf(items), List.copyOf(tombstones));
        } catch (Exception e) {
            throw new IllegalStateException("对端增量响应解析失败", e);
        }
    }

    /**
     * items 元素投影：kind=note 取 note 嵌套，kind=topic 取 topic 嵌套。
     * 缺 kind/缺嵌套/缺 name 的条目跳过（脏数据防御）。
     * <p>
     * contentUrl 路径段与 plugin-goo RoutePaths.GooRoutes 约定一致：
     * note → {origin}{prefix}/u/{owner}（无咕咕详情页，落作者页）；
     * topic → {origin}{prefix}/t/{name}。
     */
    static PulledItem parseItem(JsonNode node, String origin, String prefix) {
        String kind = node.path("kind").asText("");
        JsonNode body = node.path("note").isObject() ? node.path("note")
            : (node.path("topic").isObject() ? node.path("topic") : null);
        if (body == null || body.path("name").asText("").isEmpty()) {
            return null;
        }
        String name = body.path("name").asText();
        boolean isNote = "note".equalsIgnoreCase(kind);
        String html = body.path("html").asText("");
        String excerpt = "";
        String title = "";
        JsonNode bodySpec = body.path("spec");
        if (isNote) {
            excerpt = htmlSnippet(html);
        } else {
            title = bodySpec.path("title").asText("");
            String specExcerpt = bodySpec.path("excerpt").asText("");
            excerpt = specExcerpt.isBlank() ? htmlSnippet(html) : specExcerpt;
        }
        String authorName = bodySpec.path("owner").asText("");
        String authorDisplay = body.path("ownerVo").path("displayName").asText("");
        String contentUrl = isNote
            ? origin + prefix + "/u/" + urlEncode(authorName)
            : origin + prefix + "/t/" + urlEncode(name);
        Instant createdAt = parseInstant(body.path("createdAt").asText(null));
        return new PulledItem(
            isNote ? "note" : "topic", name, title, excerpt, html,
            authorName, authorDisplay, contentUrl, createdAt);
    }

    /** 裸 html 剥标签折叠空白（摘要兜底；入库前仍有 ContentSanitizer 全量消毒） */
    static String htmlSnippet(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html.replaceAll("<[^>]+>", " ")
            .replaceAll("\\s+", " ")
            .strip();
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String urlEncode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /** 站点根 → origin（scheme://host[:port]，端口仅非默认时保留） */
    static String siteOrigin(String siteUrl) {
        String url = siteUrl == null ? "" : siteUrl.trim();
        if (url.matches("(?i)^https?://.+")) {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            int port = uri.getPort();
            String hostPort = uri.getHost() == null ? "" : uri.getHost()
                + (port == -1 || (scheme.equalsIgnoreCase("https") && port == 443)
                || (scheme.equalsIgnoreCase("http") && port == 80) ? "" : ":" + port);
            return scheme.toLowerCase() + "://" + hostPort.toLowerCase();
        }
        return url;
    }
}
