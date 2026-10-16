package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.k8s.dto.VolumeExpansion;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这块存储类**真能不能**在线扩容」的判据（纯函数）。
 *
 * <p>2026-10-16 真撞到：PVC 的 `spec.resources.requests.storage` 从 10Gi 改成了 15Gi，
 * 而 `status.capacity` 一动不动、**连一个条件都没挂** —— 因为 `allowVolumeExpansion: true`
 * 只表示「允许扩」，真正执行的是 CSI 驱动的 `external-resizer`，而本集群两个 SC
 * （nfs-subdir / local-path）都不是 CSI 驱动。用户对着一个不动的数字猜了半天。
 */
class CoreV1StorageClassOperationsTest {

    private static String verdict(Boolean allow, String provisioner, Set<String> csiDrivers) {
        StorageClassDTO dto = new StorageClassDTO();
        dto.setAllowVolumeExpansion(allow);
        dto.setProvisioner(provisioner);
        CoreV1StorageClassOperations.fillExpansion(dto, csiDrivers);
        return dto.getExpansion();
    }

    @Test
    void bothClusterFactsDecideTheVerdict() {
        // ① 开关关着 → 明确不支持（就算它是 CSI 也一样）
        assertThat(verdict(false, "ebs.csi.aws.com", Set.of("ebs.csi.aws.com")))
                .isEqualTo(VolumeExpansion.UNSUPPORTED);
        // ② 开关开着 + provisioner 是**已注册的** CSI 驱动 → 正常情况下有人执行
        assertThat(verdict(true, "ebs.csi.aws.com", Set.of("ebs.csi.aws.com")))
                .isEqualTo(VolumeExpansion.CSI);
        // ③ 开关开着，但 provisioner 不是 CSI 驱动 → 没人会执行（本集群两个 SC 就是这一态）
        assertThat(verdict(true, "cluster.local/nfs-client-nfs-subdir-external-provisioner",
                Set.of("csi.tigera.io")))
                .isEqualTo(VolumeExpansion.UNBACKED);
    }

    @Test
    void aCsiLookingNameWithoutARegisteredDriverDoesNotCount() {
        // ⚠️ **不嗅探名字**：`driver.longhorn.io` 看着像 CSI，但集群里没有同名的 CSIDriver
        //    （驱动没装 / 没注册）→ 那就没人会执行。名字像不等于能扩。
        assertThat(verdict(true, "driver.longhorn.io", Set.of()))
                .isEqualTo(VolumeExpansion.UNBACKED);
        assertThat(verdict(true, "ebs.csi.aws.com", Set.of()))
                .isEqualTo(VolumeExpansion.UNBACKED);
    }

    @Test
    void aMissingSwitchIsTreatedAsUnsupported() {
        // 字段没写（null）也是"没有允许" —— 别把它当成 true
        assertThat(verdict(null, "ebs.csi.aws.com", Set.of("ebs.csi.aws.com")))
                .isEqualTo(VolumeExpansion.UNSUPPORTED);
    }
}
