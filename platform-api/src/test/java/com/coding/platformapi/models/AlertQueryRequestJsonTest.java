package com.coding.platformapi.models;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 游标必须**保留毫秒**。
 *
 * <p>{@code starts_at} 列是 {@code DATETIME(3)}，列表游标是 {@code (startsAt, id)}；
 * 全局默认的 {@code java.util.Date} 格式是 {@code yyyy-MM-dd HH:mm:ss}（见 JsonMapperConfig），
 * 用它解析会**静默丢掉** {@code .123}，于是下一页的 {@code starts_at = cursor} 永远不成立
 * —— 症状是「加载更多」返回 0 行，而页面看起来只是"没有更多了"。
 */
class AlertQueryRequestJsonTest {

    private static JsonMapper appMapper() {
        return JsonMapper.builder()
                .defaultDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))
                .defaultTimeZone(TimeZone.getTimeZone("GMT+8"))
                .build();
    }

    @Test
    void the_cursor_keeps_milliseconds() {
        AlertQueryRequest r = appMapper().readValue(
                "{\"clusterId\":\"c1\",\"cursorStartsAt\":\"2026-10-17 03:00:00.123\"}",
                AlertQueryRequest.class);

        assertThat(r.getCursorStartsAt().getTime() % 1000).isEqualTo(123);
    }

    /**
     * 不带毫秒的游标**会被拒**（而不是静默截断）。
     *
     * <p>这是有意选的：只有本平台前端会传这个字段，它从上一页的 {@code startsAt} 原样回传，
     * 一定带毫秒。宁可让手工调用看到一句明确的格式错误，也不要静默截断成 0 行。
     */
    @Test
    void a_cursor_without_milliseconds_is_rejected_rather_than_silently_truncated() {
        assertThatThrownBy(() -> appMapper().readValue(
                "{\"clusterId\":\"c1\",\"cursorStartsAt\":\"2026-10-17 03:00:00\"}",
                AlertQueryRequest.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }
}
