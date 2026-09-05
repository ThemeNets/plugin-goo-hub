package com.themenets.plugingoohub.service.impl;

import static com.themenets.plugingoohub.constants.HubRoutes.FOLLOW_USER;
import static run.halo.app.extension.index.query.Queries.equal;

import com.themenets.plugingoohub.extension.FedFollow;
import com.themenets.plugingoohub.extension.FedSite;
import com.themenets.plugingoohub.service.FollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 用户订阅实现（阶段四）。
 * <p>
 * 当前用户取自 Spring Security 上下文（Halo 匿名请求的 authentication name
 * 为 "anonymousUser"，视为未登录）；订阅关系锚点
 * fedfollow- + (username:siteName) 摘要，关注幂等、取消静默；
 * 关注前校验站点已收录（FedSite 存在）。
 */
@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final ReactiveExtensionClient client;

    /** 当前登录用户名（匿名/未认证 → Mono.empty） */
    public static Mono<String> currentUser() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(a -> a != null && a.isAuthenticated()
                && !"anonymousUser".equals(a.getName()))
            .map(Authentication::getName);
    }

    /** 订阅关系锚点：fedfollow- + (username:siteName) 摘要（幂等登记锚点） */
    static String followName(String username, String siteName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((username + ":" + siteName).getBytes(StandardCharsets.UTF_8));
            return "fedfollow-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return "fedfollow-"
                + Integer.toHexString((username + ":" + siteName).hashCode());
        }
    }

    @Override
    public Mono<List<String>> listFollows(String username) {
        if (username == null || username.isBlank()) {
            return Mono.just(List.of());
        }
        return client.listBy(FedFollow.class,
                ListOptions.builder().fieldQuery(equal(FOLLOW_USER, username)).build(),
                PageRequestImpl.of(1, 1000, org.springframework.data.domain.Sort.unsorted()))
            .map(result -> {
                List<String> out = new ArrayList<>();
                for (FedFollow f : result.getItems()) {
                    if (f.getSpec() != null && f.getSpec().getSiteName() != null
                        && !f.getSpec().getSiteName().isBlank()) {
                        out.add(f.getSpec().getSiteName());
                    }
                }
                return out;
            });
    }

    @Override
    public Mono<Void> follow(String username, String siteName) {
        if (username == null || username.isBlank()) {
            return Mono.error(new IllegalArgumentException("未登录"));
        }
        if (siteName == null || siteName.isBlank()) {
            return Mono.error(new IllegalArgumentException("siteName 必填"));
        }
        // 站点必须已收录
        return client.fetch(FedSite.class, siteName)
            .switchIfEmpty(Mono.error(
                new NoSuchElementException("站点未收录: " + siteName)))
            .flatMap(s -> {
                String name = followName(username, siteName);
                return client.fetch(FedFollow.class, name)
                    .switchIfEmpty(Mono.defer(() -> {
                        FedFollow f = new FedFollow();
                        Metadata m = new Metadata();
                        m.setName(name);
                        f.setMetadata(m);
                        f.setSpec(new FedFollow.FedFollowSpec());
                        f.getSpec().setUsername(username);
                        f.getSpec().setSiteName(siteName);
                        f.getSpec().setCreatedAt(Instant.now());
                        return client.create(f);
                    }))
                    .then();
            });
    }

    @Override
    public Mono<Void> unfollow(String username, String siteName) {
        if (username == null || username.isBlank()) {
            return Mono.error(new IllegalArgumentException("未登录"));
        }
        if (siteName == null || siteName.isBlank()) {
            return Mono.error(new IllegalArgumentException("siteName 必填"));
        }
        String name = followName(username, siteName);
        return client.fetch(FedFollow.class, name)
            .flatMap(f -> client.delete(f))
            .then();
    }
}
