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

    private final PipelineService svc = new PipelineService(mapper, allocationMapper, k8s, auth);

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

    @Test
    void createRejectsANamespaceTheTenantDoesNotOwn() {
        when(auth.hatTenantId()).thenReturn("t1");
        when(allocationMapper.selectByTenantClusterNs("t1", "c1", "ns1")).thenReturn(null);

        assertThatThrownBy(() -> svc.create(req("good-name", "ns1")))
                .isInstanceOf(CloudPlatformException.class);
        verify(mapper, never()).insert(any());
    }

    @Test
    void platformAdminWithoutAHatSkipsTheAllocationCheck() {
        // 平台管理员代操作时不去翻分配表 —— 与 PersistentVolumeService 同一条判断
        asPlatformAdmin();
        when(mapper.selectByKey(anyString(), anyString(), anyString())).thenReturn(null);

        svc.create(req("good-name", "ns1"));

        verify(allocationMapper, never()).selectByTenantClusterNs(any(), any(), any());
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
