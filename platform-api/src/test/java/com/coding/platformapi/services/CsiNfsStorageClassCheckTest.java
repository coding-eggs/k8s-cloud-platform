package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.platformapi.components.ReleaseSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * nfs-csi 存储类的下发前判定：比的是**集群里实际的**存储类，不是上次成功操作的快照。
 *
 * <p>2026-11-07 真撞到：为了把回收策略改成 Delete 先删了存储类，表单照样被「已安装不能改」拦下 ——
 * 旧实现只看快照，存储类在不在它不管，改这几项就没有一条走得通的路。
 */
class CsiNfsStorageClassCheckTest {

    private static ReleaseSpec release(String server, String share, String reclaimPolicy) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("server", server);
        params.put("share", share);
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("parameters", params);
        if (reclaimPolicy != null) {
            sc.put("reclaimPolicy", reclaimPolicy);
        }
        return new ReleaseSpec("platform-csi-driver-nfs", "kube-system", "csi-driver-nfs", "4.13.6",
                Map.of("storageClass", sc), Map.of());
    }

    private static StorageClassDTO live(String server, String share, String reclaimPolicy) {
        StorageClassDTO dto = new StorageClassDTO();
        dto.setName("nfs-csi");
        dto.setParameters(Map.of("server", server, "share", share));
        dto.setReclaimPolicy(reclaimPolicy);
        return dto;
    }

    private static StorageClassDTO other() {
        StorageClassDTO dto = new StorageClassDTO();
        dto.setName("nfs-client");
        dto.setReclaimPolicy("Delete");
        dto.setParameters(Map.of("archiveOnDelete", "true"));
        return dto;
    }

    @Test
    void absentStorageClassMayBeRecreatedWithNewValues() {
        // 先删存储类、再保存：这是改回收策略唯一走得通的路
        assertThat(ComponentInstallService.csiNfsStorageClassProblem(
                release("192.168.85.180", "/home/data/nfsdata", "Delete"), List.of(other()))).isNull();
    }

    @Test
    void matchingLiveStorageClassPassesEvenIfSnapshotIsStale() {
        // 集群已手工按 Delete 重建、快照还记着 Retain：对齐到集群不该被拦
        assertThat(ComponentInstallService.csiNfsStorageClassProblem(
                release("192.168.85.180", "/home/data/nfsdata", "Delete"),
                List.of(other(), live("192.168.85.180", "/home/data/nfsdata", "Delete")))).isNull();
    }

    @Test
    void changingReclaimPolicyOfExistingStorageClassIsRejectedWithHowTo() {
        String problem = ComponentInstallService.csiNfsStorageClassProblem(
                release("192.168.85.180", "/home/data/nfsdata", "Retain"),
                List.of(live("192.168.85.180", "/home/data/nfsdata", "Delete")));
        assertThat(problem).contains("回收策略 Delete").contains("请先删除该存储类");
    }

    @Test
    void changingNfsAddressOfExistingStorageClassIsRejected() {
        assertThat(ComponentInstallService.csiNfsStorageClassProblem(
                release("192.168.85.181", "/home/data/nfsdata", "Delete"),
                List.of(live("192.168.85.180", "/home/data/nfsdata", "Delete")))).isNotNull();
    }

    @Test
    void missingReclaimPolicyMeansKubernetesDefaultDelete() {
        assertThat(ComponentInstallService.csiNfsStorageClassProblem(
                release("192.168.85.180", "/home/data/nfsdata", null),
                List.of(live("192.168.85.180", "/home/data/nfsdata", "Delete")))).isNull();
    }

    @Test
    void oldK8sServerWithoutParametersOnlyComparesReclaimPolicy() {
        // 先发 platform-api、k8s-server 还是旧版：parameters 读回 null，不能因此拦掉全部编辑
        StorageClassDTO old = live("x", "y", "Delete");
        old.setParameters(null);
        assertThat(ComponentInstallService.csiNfsStorageClassProblem(
                release("192.168.85.180", "/home/data/nfsdata", "Delete"), List.of(old))).isNull();
        assertThat(ComponentInstallService.csiNfsStorageClassProblem(
                release("192.168.85.180", "/home/data/nfsdata", "Retain"), List.of(old))).isNotNull();
    }

    @Test
    void blankNfsAddressIsStillRequired() {
        assertThat(ComponentInstallService.csiNfsStorageClassProblem(
                release("", "/home/data/nfsdata", "Delete"), List.of()))
                .isEqualTo("请填写 NFS 服务地址和导出目录");
    }
}
