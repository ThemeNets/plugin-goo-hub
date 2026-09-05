package com.themenets.plugingoohub.endpoint;

import static com.themenets.plugingoohub.constants.HubRoutes.API_GROUP;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GalaxyPageBuilderTest {

    @Test
    void 页面包含关键结构与数据端点() {
        String html = GalaxyPageBuilder.build();

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("咕咕星系");
        // 两个 Tab
        assertThat(html).contains("站点目录");
        assertThat(html).contains("全网时间线");
        // 数据端点指向 hub API group（固定 group，页面 JS 以 var API 基路径 + 端点路径拼接）
        assertThat(html).contains("/apis/" + API_GROUP + "/v1alpha1");
        assertThat(html).contains("'/federation/sites'");
        assertThat(html).contains("'/federation/items?");
        // 数据注入安全约定：textContent 注入，绝不 innerHTML 拼接用户数据
        assertThat(html).contains("textContent");
        // 不存在未替换的模板残留
        assertThat(html).doesNotContain("{{");
    }

    @Test
    void 页面构建确定性输出() {
        // 纯静态壳：两次构建逐字节一致（无时间戳/随机数插值）
        assertThat(GalaxyPageBuilder.build()).isEqualTo(GalaxyPageBuilder.build());
    }
}
