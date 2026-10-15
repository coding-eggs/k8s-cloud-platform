package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.StorageClassProvisionerDTO;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.platformapi.k8s.K8sPipelineClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRetentionAndCapabilityTest {

    // ---- 保留清理 ----

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final PlatformPipelineRunLogMapper logMapper = mock(PlatformPipelineRunLogMapper.class);
    private final PipelineRetentionService retention =
            new PipelineRetentionService(runMapper, logMapper, 90);

    private static PlatformPipelineRun run(String id) {
        PlatformPipelineRun r = new PlatformPipelineRun();
        r.setId(id);
        return r;
    }

    @Test
    void purgesRunsOlderThanTheRetentionWindowAndTheirLogs() {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        when(runMapper.selectCompletedBefore("2026-07-17T00:00:00Z"))
                .thenReturn(List.of(run("r1"), run("r2")));

        int n = retention.purge(now);

        assertThat(n).isEqualTo(2);
        verify(logMapper).deleteByRunIds(List.of("r1", "r2"));
        verify(runMapper).deleteByIds(List.of("r1", "r2"));
    }

    @Test
    void theLogsAreDeletedBeforeTheRuns() {
        // 本仓不加外键，级联靠代码 —— 顺序反了就会留下没人认领的日志行
        when(runMapper.selectCompletedBefore(anyString())).thenReturn(List.of(run("r1")));

        retention.purge(Instant.parse("2026-10-15T00:00:00Z"));

        var o = org.mockito.Mockito.inOrder(logMapper, runMapper);
        o.verify(logMapper).deleteByRunIds(any());
        o.verify(runMapper).deleteByIds(any());
    }

    @Test
    void ninetyDaysAgoIsTheBoundaryNotEightyNine() {
        // 截止点必须正好是 now - 90 天（89 天前的留在库里）
        retention.purge(Instant.parse("2026-10-15T00:00:00Z"));
        verify(runMapper).selectCompletedBefore("2026-07-17T00:00:00Z");
    }

    @Test
    void nothingToPurgeMeansNoDeletesAtAll() {
        when(runMapper.selectCompletedBefore(anyString())).thenReturn(List.of());

        assertThat(retention.purge(Instant.parse("2026-10-15T00:00:00Z"))).isZero();
        verify(logMapper, never()).deleteByRunIds(any());
        verify(runMapper, never()).deleteByIds(any());
    }

    // ---- capability 门控 ----

    private final ClusterCapabilityService clusterCapability = mock(ClusterCapabilityService.class);
    private final K8sPipelineClient k8s = mock(K8sPipelineClient.class);
    private final PipelineCapabilityService capability =
            new PipelineCapabilityService(clusterCapability, k8s);

    private static StorageClassProvisionerDTO sc(String provisioner) {
        StorageClassProvisionerDTO d = new StorageClassProvisionerDTO();
        d.setName("sc-" + provisioner.hashCode());
        d.setProvisioner(provisioner);
        return d;
    }

    @Test
    void withoutTektonTheFeatureIsUnavailableWithAReason() {
        when(clusterCapability.hasTekton("c1")).thenReturn(false);

        var c = capability.available("c1");

        assertThat(c.available()).isFalse();
        assertThat(c.reason()).contains("Tekton");
        verify(k8s, never()).storageClassProvisioners(anyString());   // 已经知道不能用了，不再去打集群
    }

    @Test
    void withoutADynamicStorageClassItIsUnavailableToo() {
        // workspace 用 volumeClaimTemplate，没有可动态供应的 StorageClass 就跑不起来（spec §3.3）
        when(clusterCapability.hasTekton("c1")).thenReturn(true);
        when(k8s.storageClassProvisioners(anyString())).thenReturn(List.of(sc("kubernetes.io/no-provisioner")));

        var c = capability.available("c1");

        assertThat(c.available()).isFalse();
        assertThat(c.reason()).contains("StorageClass");
    }

    @Test
    void withBothItIsAvailable() {
        when(clusterCapability.hasTekton("c1")).thenReturn(true);
        when(k8s.storageClassProvisioners(anyString())).thenReturn(List.of(sc("rancher.io/local-path")));

        assertThat(capability.available("c1").available()).isTrue();
    }

    @Test
    void aFailedProbeIsTreatedAsUnavailableNotAsAvailable() {
        // fail-closed：CI 跑起来卡在 PVC 绑定等待上，比"暂时不可用"更糟
        when(clusterCapability.hasTekton("c1")).thenReturn(true);
        when(k8s.storageClassProvisioners(anyString())).thenThrow(new RuntimeException("cluster down"));

        assertThat(capability.available("c1").available()).isFalse();
    }

    @Test
    void aBlankProvisionerDoesNotCountAsDynamic() {
        when(clusterCapability.hasTekton("c1")).thenReturn(true);
        when(k8s.storageClassProvisioners(anyString())).thenReturn(List.of(sc("  ")));

        assertThat(capability.available("c1").available()).isFalse();
    }
}
