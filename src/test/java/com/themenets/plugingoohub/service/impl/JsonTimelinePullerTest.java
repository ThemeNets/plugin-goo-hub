package com.themenets.plugingoohub.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.themenets.plugingoohub.service.TimelinePuller;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JsonTimelinePullerTest {

    private static final String SITE = "https://sea.example.com";

    private static final String DELTA_JSON = """
        {"items": [
           {"kind": "note",
            "note": {"name": "note-001", "html": "<p>你好，海崖</p>",
                     "spec": {"owner": "ashan"}, "ownerVo": {"displayName": "阿山"},
                     "createdAt": "2026-09-05T10:00:00Z"}},
           {"kind": "topic",
            "topic": {"name": "topic-9f", "html": "<p>话题正文</p>",
                      "spec": {"title": "问个问题", "excerpt": "摘要", "owner": "lisi"},
                      "ownerVo": {"displayName": "李四"},
                      "createdAt": "2026-09-05T11:00:00Z"}},
           {"kind": "note"},
           {"kind": "note", "note": {"html": "<p>缺 name</p>"}}
         ],
         "tombstones": [
           {"name": "note-000", "kind": "note", "deletedAt": "2026-09-05T12:00:00Z"}
         ]}
        """;

    @Test
    void 解析对端增量并拼好源站链接() {
        TimelinePuller.PulledDelta delta =
            JsonTimelinePuller.parseDelta(DELTA_JSON, SITE, "/goo");

        // 脏条目（缺 name/缺嵌套）跳过 → 只剩 2 条
        assertThat(delta.items()).hasSize(2);

        TimelinePuller.PulledItem note = delta.items().get(0);
        assertThat(note.kind()).isEqualTo("note");
        assertThat(note.sourceName()).isEqualTo("note-001");
        assertThat(note.authorName()).isEqualTo("ashan");
        assertThat(note.authorDisplay()).isEqualTo("阿山");
        assertThat(note.excerpt()).isEqualTo("你好，海崖");
        assertThat(note.createdAt()).isEqualTo(Instant.parse("2026-09-05T10:00:00Z"));
        // note 无详情页 → 落作者页
        assertThat(note.contentUrl())
            .isEqualTo("https://sea.example.com/goo/u/ashan");

        TimelinePuller.PulledItem topic = delta.items().get(1);
        assertThat(topic.kind()).isEqualTo("topic");
        assertThat(topic.sourceName()).isEqualTo("topic-9f");
        assertThat(topic.title()).isEqualTo("问个问题");
        assertThat(topic.excerpt()).isEqualTo("摘要");
        assertThat(topic.contentUrl())
            .isEqualTo("https://sea.example.com/goo/t/topic-9f");

        assertThat(delta.tombstones()).hasSize(1);
        assertThat(delta.tombstones().get(0).name()).isEqualTo("note-000");
        assertThat(delta.tombstones().get(0).deletedAt())
            .isEqualTo(Instant.parse("2026-09-05T12:00:00Z"));
    }

    @Test
    void 路由前缀缺省按goo() {
        TimelinePuller.PulledDelta delta =
            JsonTimelinePuller.parseDelta(DELTA_JSON, SITE, null);
        assertThat(delta.items().get(0).contentUrl())
            .isEqualTo("https://sea.example.com/goo/u/ashan");
    }

    @Test
    void 坏响应整体报错() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> JsonTimelinePuller.parseDelta("not-json", SITE, "/goo"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void origin端口处理() {
        assertThat(JsonTimelinePuller.siteOrigin("https://a.example.com:443"))
            .isEqualTo("https://a.example.com");
        assertThat(JsonTimelinePuller.siteOrigin("http://a.example.com:8080/x"))
            .isEqualTo("http://a.example.com:8080");
        assertThat(JsonTimelinePuller.siteOrigin("https://B.example.com"))
            .isEqualTo("https://b.example.com");
    }

    @Test
    void html摘要剥标签折叠空白() {
        assertThat(JsonTimelinePuller.htmlSnippet("<p>你好</p>\n<b>世界</b>"))
            .isEqualTo("你好 世界");
        assertThat(JsonTimelinePuller.htmlSnippet("")).isEmpty();
    }
}
