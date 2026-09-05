package com.themenets.plugingoohub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.themenets.plugingoohub.service.CapabilitiesClient.CapabilitiesVo;
import org.junit.jupiter.api.Test;

/** capabilities JSON 解析测试（容错解析：缺字段默认值 / 非法 JSON 兜底） */
class CapabilitiesClientTest {

    @Test
    void 完整JSON解析全字段() {
        String json = """
            {"feedVersion": 1, "kinds": ["note", "topic"],
             "site": {"title": "海崖站", "subtitle": "副标题", "description": "简介",
                      "url": "https://sea.example.com/"},
             "routePrefix": "/goo",
             "noteCount": 12, "topicCount": 3}
            """;
        CapabilitiesVo vo = CapabilitiesClient.parse(json);
        assertThat(vo.feedVersion()).isEqualTo(1);
        assertThat(vo.kinds()).containsExactly("note", "topic");
        assertThat(vo.title()).isEqualTo("海崖站");
        assertThat(vo.subtitle()).isEqualTo("副标题");
        assertThat(vo.description()).isEqualTo("简介");
        assertThat(vo.siteUrl()).isEqualTo("https://sea.example.com/");
        assertThat(vo.routePrefix()).isEqualTo("/goo");
        assertThat(vo.noteCount()).isEqualTo(12);
        assertThat(vo.topicCount()).isEqualTo(3);
    }

    @Test
    void 缺字段容错取默认值() {
        CapabilitiesVo vo = CapabilitiesClient.parse("{\"feedVersion\": 1}");
        assertThat(vo.feedVersion()).isEqualTo(1);
        assertThat(vo.kinds()).isEmpty();
        assertThat(vo.title()).isEmpty();
        assertThat(vo.siteUrl()).isEmpty();
        assertThat(vo.noteCount()).isZero();
        assertThat(vo.topicCount()).isZero();
    }

    @Test
    void 非法JSON返回EMPTY兼容标记() {
        CapabilitiesVo vo = CapabilitiesClient.parse("not json at all");
        assertThat(vo.feedVersion()).isZero(); // 0 = 不兼容，登记侧据此拒绝
        assertThat(vo.kinds()).isEmpty();
    }
}
