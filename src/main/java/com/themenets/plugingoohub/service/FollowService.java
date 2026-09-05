package com.themenets.plugingoohub.service;

import com.themenets.plugingoohub.extension.FedFollow;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 用户订阅（阶段四）：hub 站用户对联邦站点的关注关系。
 * <p>
 * 全部操作以"当前登录用户"为主体（匿名 → Mono.empty / 401）；
 * 站点名必须已收录（FedSite 存在），订阅关系按 (username, siteName) 幂等。
 */
public interface FollowService {

    /** 我关注的站点名列表（FedSite metadata.name 集合） */
    Mono<List<String>> listFollows(String username);

    /** 关注站点：站点须已收录；重复关注幂等 */
    Mono<Void> follow(String username, String siteName);

    /** 取消关注：未关注时静默成功 */
    Mono<Void> unfollow(String username, String siteName);
}
