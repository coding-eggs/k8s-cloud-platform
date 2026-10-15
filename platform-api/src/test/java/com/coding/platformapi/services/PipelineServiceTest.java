package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PipelineDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.system.PlatformPipelineMapper;
import com.coding.data.models.system.PlatformPipeline;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.security.AuthContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 流水线定义。
 *
 * <p>⚠️ <b>渲染不在这里</b>（spec §7 的改定块）：`platform-api` 不依赖 k8s-core/fabric8，
 * 「画布 → PipelineSpec/YAML」以及节点元数据都由 **k8s-server** 做，本服务只是转发 + 管自己那张表。
 * 所以这里的桩是 {@link K8sPipelineClient}，不是渲染器。
 */
class PipelineServiceTest {

    private static final String CANVAS = """
            {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";

    private final PlatformPipelineMapper mapper = mock(PlatformPipelineMapper.class);
    private final PlatformTenantNamespaceMapper allocationMapper = mock(PlatformTenantNamespaceMapper.class);
    private final K8sPipelineClient k8s = mock(K8sPipelineClient.class);
    private final AuthContext auth = mock(AuthContext.class);
    private final NamespaceAccessGuard access = mock(NamespaceAccessGuard.class);
    private final PipelineCapabilityService capability = mock(PipelineCapabilityService.class);

    private final PipelineService svc = new PipelineService(mapper, access, k8s, auth, capability);

    @Test
    void theCapabilityProbeIsAnsweredForTheClusterItWasAskedAbout() {
        // ⭐ 在接上这个端点之前，PipelineCapabilityService 是**死代码**：算得出「这个集群能不能跑 CI」，
        //    却没有任何人问它 —— 于是页面照旧让用户点「运行」，然后收一个 500
        when(capability.available("c1")).thenReturn(new PipelineCapabilityService.Capability(
                false, "该集群未安装 Tekton（没有 tekton.dev 资源）"));

        var c = svc.capability("c1");

        assertThat(c.available()).isFalse();
        assertThat(c.reason()).contains("Tekton");
    }

    @Test
    void theCapabilityProbeIsNotNamespaceScopedOnPurpose() {
        // 它回答的是**集群能力**（有没有 Tekton、有没有可动态供应的 StorageClass），
        // 不含任何资源数据 —— 同 /cluster/capability/get 那条判断。
        // ⚠️ 加上命名空间归属校验反而会把页面级门控弄坏：门控要在**没有**命名空间上下文时也答得出来
        svc.capability("c1");

        verify(access, never()).assertAccessible(any(), any());
    }

    private static PipelineDTO req(String name, String namespace) {
        PipelineDTO d = new PipelineDTO();
        d.setClusterId("c1");
        d.setNamespace(namespace);
        d.setName(name);
        d.setCanvasJson(CANVAS);
        return d;
    }

    /** 平台管理员（无租户帽）—— 大部分用例只关心 CRUD 本身，用这个省掉分配表打桩。 */
    private void asPlatformAdmin() {
        when(auth.hatTenantId()).thenReturn(null);
    }

    // ---- 透传给 k8s-server 的两个动作 ----

    @Test
    void nodeTypesArePassedThroughFromK8sServer() {
        JsonNode payload = JsonMapper.builder().build().readTree("[{\"type\":\"git\"}]");
        when(k8s.nodeTypes()).thenReturn(payload);

        assertThat(svc.nodeTypes()).isEqualTo(payload);
    }

    @Test
    void previewIsDelegatedToK8sServer() {
        when(k8s.render(CANVAS)).thenReturn("kind: PipelineSpec\n");

        assertThat(svc.preview(CANVAS)).isEqualTo("kind: PipelineSpec\n");
    }

    @Test
    void aBadCanvasIsRejectedByK8sServerAndNeverStored() {
        // ⭐ Review Focus #1：带环的画布必须在**保存时**就被拒 —— 规则只有一份，在 k8s-server 那边
        asPlatformAdmin();
        when(k8s.render(CANVAS)).thenThrow(new CloudPlatformException(10040, "流水线里有一个环"));

        assertThatThrownBy(() -> svc.create(req("good-name", "ns1")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("环");
        verify(mapper, never()).insert(any());
    }

    // ---- 名字 ----

    @Test
    void aPipelineNameMustBeDnsLabelish() {
        assertThatThrownBy(() -> svc.validateName("Bad_Name"))
                .isInstanceOf(CloudPlatformException.class);
        svc.validateName("good-name-1");
    }

    // ---- CRUD ----

    @Test
    void createValidatesTheCanvasThenStoresIt() {
        asPlatformAdmin();
        when(mapper.selectByKey("c1", "ns1", "good-name")).thenReturn(null);

        PipelineDTO out = svc.create(req("good-name", "ns1"));

        assertThat(out.getId()).isNotBlank();
        assertThat(out.getName()).isEqualTo("good-name");
        verify(k8s).render(CANVAS);                       // 先校验
        verify(mapper).insert(any(PlatformPipeline.class));
    }

    @Test
    void createRejectsADuplicateNameWithinTheNamespace() {
        asPlatformAdmin();
        PlatformPipeline existing = new PlatformPipeline();
        existing.setId("p-existing");
        when(mapper.selectByKey("c1", "ns1", "dup")).thenReturn(existing);

        assertThatThrownBy(() -> svc.create(req("dup", "ns1")))
                .isInstanceOf(CloudPlatformException.class);
        verify(mapper, never()).insert(any());
    }

    /**
     * 归属校验**委托给 {@link NamespaceAccessGuard}**（规则本身在它自己的测试里钉）。
     * 这里只断言「问过了、并且问的是这条流水线真正的一对 cluster/namespace」——
     * 校验写在哪个方法里、用哪个字段，才是这一层会出错的地方。
     */
    @Test
    void createAsksTheGuardAboutThePipelinesNamespace() {
        asPlatformAdmin();
        when(mapper.selectByKey(anyString(), anyString(), anyString())).thenReturn(null);

        svc.create(req("good-name", "ns1"));

        verify(access).assertAccessible("c1", "ns1");
    }

    @Test
    void aRefusedNamespaceStopsBeforeAnythingIsWritten() {
        doThrow(new CloudPlatformException(10008, "无权访问该命名空间：ns1"))
                .when(access).assertAccessible(any(), any());

        assertThatThrownBy(() -> svc.create(req("good-name", "ns1")))
                .isInstanceOf(CloudPlatformException.class);
        verify(mapper, never()).insert(any());
    }

    @Test
    void listReturnsOnlyTheRequestedNamespace() {
        asPlatformAdmin();
        PlatformPipeline p = new PlatformPipeline();
        p.setId("p1");
        p.setClusterId("c1");
        p.setNamespace("ns1");
        p.setName("a");
        when(mapper.listByNamespace("c1", "ns1")).thenReturn(List.of(p));

        assertThat(svc.list(req("ignored", "ns1")))
                .singleElement()
                .satisfies(d -> assertThat(d.getId()).isEqualTo("p1"));
    }

    @Test
    void getOfAMissingPipelineIsRejectedNotReturnedAsNull() {
        assertThatThrownBy(() -> svc.get("nope"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void deleteRemovesTheRow() {
        asPlatformAdmin();
        PlatformPipeline p = new PlatformPipeline();
        p.setId("p1");
        p.setClusterId("c1");
        p.setNamespace("ns1");
        when(mapper.selectById("p1")).thenReturn(p);

        svc.delete("p1");

        verify(mapper).deleteById("p1");
    }

    @Test
    void updateKeepsTheStoredNamespaceAndCannotBeMoved() {
        // 流水线的归属是 (cluster, namespace)，跟着它一生的所有运行 —— 不允许被改名搬走
        asPlatformAdmin();
        PlatformPipeline stored = new PlatformPipeline();
        stored.setId("p1");
        stored.setClusterId("c1");
        stored.setNamespace("ns1");
        stored.setName("old-name");
        when(mapper.selectById("p1")).thenReturn(stored);
        when(mapper.selectByKey("c1", "ns1", "new-name")).thenReturn(null);

        PipelineDTO req = req("new-name", "somewhere-else");
        req.setId("p1");
        PipelineDTO out = svc.update(req);

        assertThat(out.getNamespace()).isEqualTo("ns1");     // 存的，不是请求里的
        verify(mapper).update(any(PlatformPipeline.class));
    }
}
