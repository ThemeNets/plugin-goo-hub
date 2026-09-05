package com.themenets.plugingoohub.service;

import com.themenets.plugingoohub.domain.vo.SiteItemVo;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 站点登记服务（阶段一）：自助登记 → 拉对端 capabilities 自动验证 → 落 FedSite 注册表。
 */
public interface RegistrationService {

    /**
     * 自助登记/刷新站点：拉对端 capabilities 自动验证（feedVersion 兼容性校验），
     * 同 URL 重复登记幂等更新 status 不重建。siteUrl 非法/不可达/不兼容均报错。
     *
     * @param rawUrl    对端站点根地址（允许不带 scheme，自动补 https://）
     * @param submitter 登记人用户名（匿名登记传空串）
     * @return 收录后的站点目录条目
     */
    Mono<SiteItemVo> register(String rawUrl, String submitter);

    /** 公开站点目录（已收录站点，title 字母序） */
    Mono<List<SiteItemVo>> listSites();
}
