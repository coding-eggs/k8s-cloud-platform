package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineDTO;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.models.PipelineRunContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunServiceTest {

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final K8sPipelineClient k8s = mock(K8sPipelineClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final PipelineArchiveService archive = mock(PipelineArchiveService.class);

    private final PipelineRunService svc =
            new PipelineRunService(runMapper, k8s, credentials, archive);

    private final PipelineRunContext ctx = new PipelineRunContext("c1", "ns1", "t1");

    private static PipelineDTO pipeline(String canvas) {
        PipelineDTO p = new PipelineDTO();
        p.setId("p1");
        p.setClusterId("c1");
        p.setNamespace("ns1");
        p.setName("my-app");
        p.setCanvasJson(canvas);
        return p;
    }

    private static String canvas(String... connectionIds) {
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < connectionIds.length; i++) {
            if (i > 0) {
                nodes.append(',');
            }
            nodes.append("{\"id\":\"n").append(i).append("\",\"type\":\"git\",\"config\":{")
                 .append("\"connectionId\":\"").append(connectionIds[i]).append("\"}}");
        }
        return "{\"nodes\":[" + nodes + "],\"edges\":[]}";
    }

    // ---- 触发 ----

    @Test
    void theRunNameIsGeneratedAndPersistedBeforeTheClusterCall() {
        // ⭐ spec §4.5①：run_name 由平台先生成 → 幂等键从源头就有，
        //    也不会出现「集群里有对象、库里没记录」的孤儿
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new PipelineRunDTO());

        String runName = svc.run(pipeline(canvas("conn-1")), "alice", ctx);

        InOrder inOrder = inOrder(runMapper, k8s);
        inOrder.verify(runMapper).insert(any());                                  // 先落库
        inOrder.verify(k8s).createRun(anyString(), any(), anyString(), anyString(), anyString());
        assertThat(runName).isNotBlank().startsWith("my-app-");
    }

    @Test
    void aFailedClusterCreateDoesNotLeaveARunningOrphan() {
        // ⭐ 先落库再创建的代价要在这里堵住
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new CloudPlatformException(EnumResponseType.ERROR, "boom"));

        assertThatThrownBy(() -> svc.run(pipeline(canvas("conn-1")), "alice", ctx))
                .isInstanceOf(CloudPlatformException.class);

        verify(runMapper).deleteById(any());        // 库里不能留一条 RUNNING 的孤儿
    }

    @Test
    void everyConnectionTheCanvasReferencesMustBeAuthorized() {
        // ⭐ Review Focus #4：绕过前端直接打接口，引用一条没被授权的连接也要被拒
        svc.run(pipeline(canvas("conn-1")), "alice", ctx);

        verify(credentials).assertUsableByPipelines("conn-1", "GIT");
    }

    @Test
    void anUnauthorizedConnectionStopsTheRunBeforeAnythingIsWritten() {
        doThrowUnauthorized();

        assertThatThrownBy(() -> svc.run(pipeline(canvas("conn-1")), "alice", ctx))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("未授权");

        verify(runMapper, never()).insert(any());
        verify(k8s, never()).createRun(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void theRegistryConnectionOfAnImageNodeIsCheckedAsARegistryConnection() {
        String withImage = """
                {"nodes":[{"id":"build","type":"image","config":{"registryConnectionId":"reg-9"}}],
                 "edges":[]}""";

        svc.run(pipeline(withImage), "alice", ctx);

        // null = 不校验类型：镜像节点只要求「是一条被授权的 registry 连接」
        verify(credentials).assertUsableByPipelines("reg-9", null);
    }

    @Test
    void theStoredRunCarriesTheTriggeredByAndTheCanvas() {
        svc.run(pipeline(canvas("conn-1")), "alice", ctx);

        ArgumentCaptor<PlatformPipelineRun> cap = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(cap.capture());
        assertThat(cap.getValue().getTriggeredBy()).isEqualTo("alice");
        assertThat(cap.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(cap.getValue().getPipelineId()).isEqualTo("p1");
        assertThat(cap.getValue().getClusterId()).isEqualTo("c1");
        assertThat(cap.getValue().getNamespace()).isEqualTo("ns1");
    }

    // ---- 取消 ----

    @Test
    void cancelCapturesLogsBeforeTellingTektonToCancel() {
        // ⭐ spec §4.2：这是「取消之后日志仍然看得到」的全部依据
        when(runMapper.selectByRunName("r1")).thenReturn(running("r1"));

        svc.cancel("r1", ctx);

        InOrder inOrder = inOrder(archive, k8s);
        inOrder.verify(archive).capture(eq("r1"), any());                       // 先抓
        inOrder.verify(k8s).cancelRun("c1", "t1", "ns1", "r1");                 // 再取消
    }

    @Test
    void cancellingAnUnknownRunIsRefused() {
        when(runMapper.selectByRunName("nope")).thenReturn(null);

        assertThatThrownBy(() -> svc.cancel("nope", ctx))
                .isInstanceOf(CloudPlatformException.class);

        verify(k8s, never()).cancelRun(anyString(), any(), anyString(), anyString());
    }

    @Test
    void theRunNameIsGloballyUniqueSoTheDbIndexNeverCollides() {
        // uk_run_name 是**全局**唯一（不按 cluster/namespace 分组），所以名字必须带时间戳 + 随机后缀
        String a = svc.run(pipeline(canvas("c")), "alice", ctx);
        String b = svc.run(pipeline(canvas("c")), "alice", ctx);

        assertThat(a).isNotEqualTo(b);
        assertThat(a).matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");   // K8s 对象名的规矩
    }

    // ---- 历史 ----

    @Test
    void historyIsReadFromTheDatabaseOnly() {
        // 集群里的对象归档后已经删了 —— 读历史一律读库（spec §4.3）
        when(runMapper.listByPipeline("p1", "c1", "ns1")).thenReturn(List.of(running("r1")));

        assertThat(svc.list("p1", ctx)).extracting(PlatformPipelineRun::getRunName).containsExactly("r1");
        verify(k8s, never()).getRun(anyString(), any(), anyString(), anyString());
    }

    private static PlatformPipelineRun running(String runName) {
        PlatformPipelineRun r = new PlatformPipelineRun();
        r.setId("run-1");
        r.setRunName(runName);
        r.setPipelineId("p1");
        r.setClusterId("c1");
        r.setNamespace("ns1");
        r.setStatus("RUNNING");
        return r;
    }

    private void doThrowUnauthorized() {
        org.mockito.Mockito.doThrow(new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "该连接未授权在流水线里使用：conn-1"))
                .when(credentials).assertUsableByPipelines(any(), any());
    }
}
