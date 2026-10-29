package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineRetryPlanDTO;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.models.PipelineRunContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 从断点继续：范围由**图**决定，不能由客户端挑集合。
 */
class PipelineRunServiceRetryTest {

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final PlatformPipelineRunLogMapper logMapper = mock(PlatformPipelineRunLogMapper.class);
    private final K8sPipelineClient k8s = mock(K8sPipelineClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final NamespaceAccessGuard access = mock(NamespaceAccessGuard.class);
    private final com.coding.platformapi.security.AuthContext auth =
            mock(com.coding.platformapi.security.AuthContext.class);
    private final PipelineRunService svc =
            new PipelineRunService(runMapper, logMapper, k8s, credentials, access, auth);

    private final PipelineRunContext ctx = new PipelineRunContext("c1", "ns1", "t1");

    /** git → a → b → c。 */
    private static final String CHAIN = """
            {"nodes":[{"id":"git","type":"shell","config":{}},
                      {"id":"a","type":"shell","config":{}},
                      {"id":"b","type":"shell","config":{}},
                      {"id":"c","type":"shell","config":{}}],
             "edges":[{"from":"git","to":"a"},{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

    private PlatformPipelineRun parent(String status, String steps) {
        PlatformPipelineRun row = new PlatformPipelineRun();
        row.setId("run-parent");
        row.setPipelineId("p1");
        row.setClusterId("c1");
        row.setNamespace("ns1");
        row.setRunName("my-app-20261020123456-aaaa");
        row.setCanvasJson(CHAIN);
        row.setStatus(status);
        row.setStepsJson(steps);
        row.setWorkspacePvc("platform-pipeline-ws-my-app-20261020123456-aaaa");
        row.setAttemptNo(1);
        return row;
    }

    private void parentIs(PlatformPipelineRun row) {
        when(runMapper.selectByRunName(row.getRunName())).thenReturn(row);
    }

    @Test
    void thePlanOnlyLetsYouStartAtBreakpoints() {
        parentIs(parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED"));

        PipelineRetryPlanDTO plan = svc.retryPlan("my-app-20261020123456-aaaa");

        assertThat(plan.retryable()).isTrue();
        assertThat(plan.retryableNodes()).containsExactly("b");
        assertThat(plan.runNodeIds()).containsExactly("b", "c");
        assertThat(plan.blocked()).containsEntry("c", "上游 b 没有成功，请从它开始重试");
    }

    @Test
    void youCannotStartAtADescendantOfAFailedNode() {
        parentIs(parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED"));

        assertThatThrownBy(() -> svc.retry("my-app-20261020123456-aaaa", "c", "alice", ctx))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("上游 b 没有成功");
        verify(runMapper, never()).insert(any());
    }

    @Test
    void retryingABreakpointRunsItsWholeSubtreeAndReusesTheRest() {
        parentIs(parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED"));
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(),
                anyString(), anyBoolean())).thenReturn(new PipelineRunDTO());

        String runName = svc.retry("my-app-20261020123456-aaaa", "b", "alice", ctx);

        ArgumentCaptor<PlatformPipelineRun> row = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(row.capture());
        PlatformPipelineRun child = row.getValue();
        assertThat(child.getParentRunId()).isEqualTo("run-parent");
        assertThat(child.getAttemptNo()).isEqualTo(2);
        assertThat(child.getRetryFromNode()).isEqualTo("b");
        assertThat(child.getWorkspacePvc()).isEqualTo("platform-pipeline-ws-my-app-20261020123456-aaaa");
        assertThat(child.getReusedNodesJson()).isEqualTo("[\"git\",\"a\"]");
        assertThat(child.getCanvasJson()).as("库里仍存完整画布").isEqualTo(CHAIN);
        assertThat(runName).startsWith("my-app-").isNotEqualTo("my-app-20261020123456-aaaa");

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(k8s).createRun(anyString(), any(), anyString(), anyString(), sent.capture(), any(),
                anyString(), anyBoolean());
        assertThat(sent.getValue()).doesNotContain("\"id\":\"a\"")
                .contains("\"id\":\"b\"").contains("\"id\":\"c\"");
    }

    @Test
    void aSucceededRunCannotBeRetried() {
        parentIs(parent("SUCCEEDED", "git=SUCCEEDED,a=SUCCEEDED,b=SUCCEEDED,c=SUCCEEDED"));

        PipelineRetryPlanDTO plan = svc.retryPlan("my-app-20261020123456-aaaa");

        assertThat(plan.retryable()).isFalse();
        assertThat(plan.reason()).contains("工作区");
    }

    @Test
    void aRunFromBeforeThisFeatureCannotBeRetried() {
        PlatformPipelineRun row = parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED");
        row.setWorkspacePvc(null);
        parentIs(row);

        assertThat(svc.retryPlan("my-app-20261020123456-aaaa").retryable()).isFalse();
    }

    @Test
    void theEffectiveStatusIncludesReusedNodes() {
        PlatformPipelineRun row = parent("FAILED", "b=FAILED");
        row.setReusedNodesJson("[\"git\",\"a\"]");

        assertThat(PipelineRunService.effectiveStatus(row))
                .containsEntry("git", "SUCCEEDED").containsEntry("a", "SUCCEEDED")
                .containsEntry("b", "FAILED");
    }

    @Test
    void legacyStepKeysWithTheRunNamePrefixAreNormalized() {
        PlatformPipelineRun row = parent("FAILED",
                "my-app-20261020123456-aaaa-git=SUCCEEDED,my-app-20261020123456-aaaa-b=FAILED");

        assertThat(PipelineRunService.effectiveStatus(row))
                .containsEntry("git", "SUCCEEDED").containsEntry("b", "FAILED");
    }

    @Test
    void anUnfinishedFinallyNodeIsRetriedAloneAndAsANormalTask() {
        String canvas = """
                {"nodes":[{"id":"a","type":"shell","config":{}},
                          {"id":"fin","type":"shell","config":{},"finallyTask":true,"finallyWhen":"failed"}],
                 "edges":[]}""";
        PlatformPipelineRun row = parent("FAILED", "a=SUCCEEDED,fin=SKIPPED");
        row.setCanvasJson(canvas);
        parentIs(row);
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(),
                anyString(), anyBoolean())).thenReturn(new PipelineRunDTO());

        svc.retry("my-app-20261020123456-aaaa", "fin", "alice", ctx);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(k8s).createRun(anyString(), any(), anyString(), anyString(), sent.capture(), any(),
                anyString(), anyBoolean());
        assertThat(sent.getValue()).doesNotContain("finallyTask").doesNotContain("finallyWhen");
    }

    @Test
    void aFailedCreateRollsBackTheNewRow() {
        parentIs(parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED"));
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(),
                anyString(), anyBoolean()))
                .thenThrow(new CloudPlatformException(
                        com.coding.common.exception.EnumResponseType.ERROR, "boom"));

        assertThatThrownBy(() -> svc.retry("my-app-20261020123456-aaaa", "b", "alice", ctx));
        verify(runMapper).deleteById(any());
    }
}
