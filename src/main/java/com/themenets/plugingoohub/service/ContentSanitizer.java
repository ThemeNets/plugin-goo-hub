package com.themenets.plugingoohub.service;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Component;

/**
 * hub 侧内容消毒（缓存端消毒惯例）：对端 html 不可信，入库前白名单再过一遍。
 * 与 plugin-goo 的 ContentSanitizer 同口径（白名单一致），但不带 Markdown 渲染——
 * 对端内容已是渲染+清洗后的 HTML。
 */
@Component
public class ContentSanitizer {

    /** 白名单：格式 + 块级 + 链接(nofollow) + 图片(http/https) + 表格 + 行内代码类标签 */
    private static final PolicyFactory POLICY = Sanitizers.FORMATTING
        .and(Sanitizers.BLOCKS)
        .and(Sanitizers.LINKS)
        .and(Sanitizers.IMAGES)
        .and(Sanitizers.TABLES)
        .and(new HtmlPolicyBuilder()
            .allowElements("code", "del", "kbd", "mark", "s", "sub", "sup")
            .toFactory());

    /** 直接净化 HTML（对端产物二次消毒） */
    public String sanitizeHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return POLICY.sanitize(html);
    }

    /** 摘要：清洗后 html 剥标签、折叠空白截断（列表展示用） */
    public String excerpt(String html, int maxLength) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html.replaceAll("<[^>]+>", " ")
            .replaceAll("\\s+", " ")
            .strip();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
