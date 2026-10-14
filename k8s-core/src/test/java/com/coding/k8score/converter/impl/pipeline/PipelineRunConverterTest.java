package com.coding.k8score.converter.impl.pipeline;

import com.coding.common.models.k8s.dto.PipelineRunDTO;
import io.fabric8.knative.pkg.apis.Condition;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.tekton.v1.PipelineRun;
import io.fabric8.tekton.v1.PipelineRunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRunConverterTest {

    private final PipelineRunConverter converter = new PipelineRunConverter();

    /**
     * ⚠️ tekton-model 的 {@code PipelineRun} **不初始化 metadata** —— `new PipelineRun()`
     * 的 `getMetadata()` 是 null（core 模型会初始化一个空的 ObjectMeta，tekton 模型不会）。
     * 所以凡是要往 PipelineRun 上写 metadata 的地方（生产和测试都一样）都得先 setMetadata。
     */
    private static PipelineRun run(String name, String namespace) {
        PipelineRun pr = new PipelineRun();
        pr.setMetadata(new ObjectMeta());
        pr.getMetadata().setName(name);
        pr.getMetadata().setNamespace(namespace);
        return pr;
    }

    @Test
    void dtoApiPathIsTheControllerMapping() {
        assertThat(new PipelineRunDTO().getApiPath()).isEqualTo("/pipelineruns");
    }

    @Test
    void readsStatusAndTimesOffTheObject() {
        PipelineRun pr = run("r1", "ns1");
        PipelineRunStatus st = new PipelineRunStatus();
        st.setConditions(List.of(condition("Succeeded", "True", "Succeeded")));
        st.setStartTime("2026-10-15T10:00:00Z");
        st.setCompletionTime("2026-10-15T10:03:00Z");
        pr.setStatus(st);

        PipelineRunDTO dto = converter.toDto(pr);

        assertThat(dto.getName()).isEqualTo("r1");
        assertThat(dto.getNamespace()).isEqualTo("ns1");
        assertThat(dto.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(dto.getStartTime()).isEqualTo("2026-10-15T10:00:00Z");
        assertThat(dto.getCompletionTime()).isEqualTo("2026-10-15T10:03:00Z");
    }

    @Test
    void runningHasNoConditionSucceededYetSoStatusIsRunning() {
        PipelineRun pr = run("r2", "ns1");
        pr.setStatus(new PipelineRunStatus());     // conditions 为 null
        assertThat(converter.toDto(pr).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void failedConditionMapsToFailed() {
        PipelineRun pr = run("r3", "ns1");
        PipelineRunStatus st = new PipelineRunStatus();
        st.setConditions(List.of(condition("Succeeded", "False", "Failed")));
        pr.setStatus(st);
        assertThat(converter.toDto(pr).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void cancelledReasonAndTimeoutReasonAreDistinguished() {
        // 平台自己取消的与平台判定超时的，状态要能分开 —— 否则运行历史里看不出区别
        assertThat(statusOf("False", "Cancelled")).isEqualTo("CANCELLED");
        assertThat(statusOf("False", "PipelineRunTimeout")).isEqualTo("TIMEOUT");
    }

    private String statusOf(String condStatus, String reason) {
        PipelineRun pr = run("r", "ns1");
        PipelineRunStatus st = new PipelineRunStatus();
        st.setConditions(List.of(condition("Succeeded", condStatus, reason)));
        pr.setStatus(st);
        return converter.toDto(pr).getStatus();
    }

    private static Condition condition(String type, String status, String reason) {
        Condition c = new Condition();
        c.setType(type);
        c.setStatus(status);
        c.setReason(reason);
        return c;
    }

    @Test
    void createDoesNotSendAStatusSubresource() {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setName("r1");
        dto.setNamespace("ns1");
        PipelineRun pr = converter.toTekton(dto);
        assertThat(pr.getMetadata().getName()).isEqualTo("r1");
        assertThat(pr.getStatus()).isNull();
    }
}
