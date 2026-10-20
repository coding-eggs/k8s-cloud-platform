package com.coding.common.models.pipeline;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 这些字段名会**原样进前端**（records 由 Jackson 序列化）。
 * 改名等于改契约，所以用测试把名字钉住。
 */
class PipelineRetryDtosTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void thePlanSerializesWithTheNamesTheFrontendReads() {
        var plan = new PipelineRetryPlanDTO(true, null,
                List.of("b"), List.of("b", "c"), List.of("a"), Map.of("c", "上游 b 没有成功"));

        String json = JSON.writeValueAsString(plan);

        assertThat(json).contains("\"retryable\":true")
                .contains("\"retryableNodes\":[\"b\"]")
                .contains("\"runNodeIds\":[\"b\",\"c\"]")
                .contains("\"reusedNodeIds\":[\"a\"]")
                .contains("\"blocked\":{\"c\":\"上游 b 没有成功\"}");
    }

    @Test
    void theWorkspaceListSerializesCountAndBytes() {
        var out = new PipelineRetryWorkspacesDTO(1, 1073741824L,
                List.of(new PipelineRetryWorkspaceDTO("ws-1", "run-1",
                        "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", 1073741824L)));

        String json = JSON.writeValueAsString(out);

        assertThat(json).contains("\"count\":1").contains("\"totalBytes\":1073741824")
                .contains("\"name\":\"ws-1\"").contains("\"retainUntil\":\"2026-10-21T00:00:00Z\"");
    }
}
