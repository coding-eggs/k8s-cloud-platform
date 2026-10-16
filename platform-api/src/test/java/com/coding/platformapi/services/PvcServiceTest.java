package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.k8s.dto.VolumeExpansion;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.models.PvcResizeRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PVC 扩容（S4.1）：**只增不减** + **存储类得开了在线扩容**，以及
 * **扩容与"只同步标签"是两个端点**这件事。
 *
 * <p>为什么这两条规则必须有：K8s 对 PVC 的 {@code spec} 是**不可变**的，唯一允许的例外就是
 * "往大改容量"，而且还得存储类开了 {@code allowVolumeExpansion}。不拦的话，用户填个更小的数
 * 会拿到一句看不懂的 K8s 原话，填个更大的数而存储类没开则会在 SSA 那一步失败。
 */
class PvcServiceTest {

    private static final long GI = 1024L * 1024 * 1024;

    private final K8sClient k8s = mock(K8sClient.class);
    private final StorageClassService storageClasses = mock(StorageClassService.class);
    private final PvcService svc = new PvcService(k8s, storageClasses);

    /** 集群上**现在**的这个 PVC（旧容量 + 它用的存储类 + 一个标签）。 */
    private PersistentVolumeClaimDTO existing(long bytes, String storageClass) {
        PersistentVolumeClaimDTO old = new PersistentVolumeClaimDTO();
        old.setName("data");
        old.setNamespace("ns1");
        old.setStorageClassName(storageClass);
        old.setAccessModes(List.of("ReadWriteOnce"));
        old.setStorage(BigDecimal.valueOf(bytes));
        old.setLabels(Map.of("keep", "me"));
        when(k8s.get(any())).thenReturn(old);
        return old;
    }

    /**
     * 这块存储类的**真实扩容能力**（三态）。⚠️ 判据不是 {@code allowVolumeExpansion} ——
     * 那个只表示「允许」，非 CSI 的 provisioner 改了也只会把请求改大（见 {@link VolumeExpansion}）。
     */
    private void expansion(String name, String verdict) {
        when(storageClasses.expansionOf("c1", name)).thenReturn(verdict);
    }

    private static PvcResizeRequest resizeTo(Long bytes) {
        PvcResizeRequest req = new PvcResizeRequest();
        req.setTenantId("t1");
        req.setClusterId("c1");
        req.setNamespace("ns1");
        req.setStorage(bytes == null ? null : BigDecimal.valueOf(bytes));
        return req;
    }

    // ---- 扩容这条端点 ----

    @Test
    void aGenuineIncreaseGoesThrough() {
        existing(10 * GI, "nfs-client");
        expansion("nfs-client", VolumeExpansion.CSI);
        when(k8s.update(any())).thenAnswer(inv -> inv.getArgument(0));

        svc.resize("data", resizeTo(20 * GI));

        verify(k8s).update(any());
    }

    @Test
    void shrinkingIsRefusedWithBothNumbersInPlainSight() {
        // K8s 根本不支持缩容 —— 这句要我们自己说，别把它的原话甩给用户
        existing(10 * GI, "nfs-client");
        expansion("nfs-client", VolumeExpansion.CSI);

        assertThatThrownBy(() -> svc.resize("data", resizeTo(5 * GI)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("10Gi")
                .hasMessageContaining("5Gi");
        verify(k8s, never()).update(any());
    }

    @Test
    void theSameSizeIsRefusedTooAndNeverTouchesTheCluster() {
        // 相等 = 什么都没改。放过去就是白跑一次 SSA，还会在集群上留一条事件
        existing(10 * GI, "nfs-client");
        expansion("nfs-client", VolumeExpansion.CSI);

        assertThatThrownBy(() -> svc.resize("data", resizeTo(10 * GI)))
                .isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).update(any());
    }

    @Test
    void aStorageClassWithoutOnlineExpansionIsRefused() {
        existing(10 * GI, "local-path");
        expansion("local-path", VolumeExpansion.UNSUPPORTED);

        assertThatThrownBy(() -> svc.resize("data", resizeTo(20 * GI)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("local-path")
                .hasMessageContaining("allowVolumeExpansion");
        verify(k8s, never()).update(any());
    }

    @Test
    void aStorageClassThatOnlySaysItAllowsExpansionIsRefusedWhenNoOneWillDoIt() {
        // ⭐ 这一态最反直觉，也是本集群两个 SC 的真实状态：`allowVolumeExpansion: true`，
        //    但 provisioner 不是 CSI 驱动（nfs-subdir / local-path）→ **没有人会执行扩容**。
        //    放过去只会让 spec 悄悄偏离 status：请求改成 20Gi，status.capacity 永远停在 10Gi，
        //    而且连一个条件都不会挂（没人在试，也就没人报错）。用户 2026-10-16 就是这么撞上的。
        existing(10 * GI, "nfs-client");
        expansion("nfs-client", VolumeExpansion.UNBACKED);

        assertThatThrownBy(() -> svc.resize("data", resizeTo(20 * GI)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nfs-client")
                .hasMessageContaining("CSI");
        verify(k8s, never()).update(any());
    }

    @Test
    void anUnreadableStorageClassIsRefusedRatherThanAssumedToBeFine() {
        // ⚠️ fail-closed：读不到就不敢说"能扩"。放过去的话，用户在 SSA 那一步拿到的
        //    会是一句 K8s 原话，比这里明确拒掉差得多
        existing(10 * GI, "gone");
        when(storageClasses.expansionOf("c1", "gone")).thenReturn(null);

        assertThatThrownBy(() -> svc.resize("data", resizeTo(20 * GI)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("gone");
        verify(k8s, never()).update(any());
    }

    @Test
    void aResizeWithoutANewSizeIsRefused() {
        existing(10 * GI, "nfs-client");

        assertThatThrownBy(() -> svc.resize("data", resizeTo(null)))
                .isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).update(any());
    }

    @Test
    void aResizeAgainstAMissingPvcIsRefusedBeforeTouchingTheCluster() {
        when(k8s.get(any())).thenReturn(null);

        assertThatThrownBy(() -> svc.resize("nope", resizeTo(20 * GI)))
                .isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).update(any());
    }

    @Test
    void theStorageClassIsReadFromWhatTheClusterActuallyHas() {
        // 请求里没有存储类字段 —— 只能也**只该**从集群上那个对象读。照调用方说的查门控
        // 等于让它自证清白
        existing(10 * GI, "nfs-client");
        expansion("nfs-client", VolumeExpansion.CSI);
        when(k8s.update(any())).thenAnswer(inv -> inv.getArgument(0));

        svc.resize("data", resizeTo(20 * GI));

        verify(storageClasses).expansionOf("c1", "nfs-client");
    }

    @Test
    void theResizeSendsBackWhatTheClusterHasNotOnlyTheNewSize() {
        // ⚠️ SSA 是会**删字段**的，而 PVC 的 `accessModes` 是必填、`storageClassName` 不可变 ——
        //    请求体里只有 storage，直接 apply 上去会被 API server 拒。所以以**读回来的那一份**为底。
        existing(10 * GI, "nfs-client");
        expansion("nfs-client", VolumeExpansion.CSI);
        when(k8s.update(any())).thenAnswer(inv -> inv.getArgument(0));

        svc.resize("data", resizeTo(20 * GI));

        ArgumentCaptor<PersistentVolumeClaimDTO> sent =
                ArgumentCaptor.forClass(PersistentVolumeClaimDTO.class);
        verify(k8s).update(sent.capture());
        assertThat(sent.getValue().getAccessModes()).containsExactly("ReadWriteOnce");
        assertThat(sent.getValue().getStorageClassName()).isEqualTo("nfs-client");
        assertThat(sent.getValue().getStorage()).isEqualByComparingTo(BigDecimal.valueOf(20 * GI));
    }

    // ---- 「只同步标签」那条端点 ----

    @Test
    void aPlainUpdateStillSyncsLabels() {
        existing(10 * GI, "nfs-client");
        when(k8s.update(any())).thenAnswer(inv -> inv.getArgument(0));

        PersistentVolumeClaimDTO body = new PersistentVolumeClaimDTO();
        body.setName("data");
        body.setTenantId("t1");
        body.setClusterId("c1");
        body.setNamespace("ns1");
        body.setLabels(Map.of("a", "b"));
        svc.update(body);

        ArgumentCaptor<PersistentVolumeClaimDTO> sent =
                ArgumentCaptor.forClass(PersistentVolumeClaimDTO.class);
        verify(k8s).update(sent.capture());
        assertThat(sent.getValue().getLabels()).containsEntry("a", "b");
        // 容量没被动过 —— 请求里没带
        assertThat(sent.getValue().getStorage()).isEqualByComparingTo(BigDecimal.valueOf(10 * GI));
    }

    @Test
    void theLabelEndpointRefusesToChangeTheCapacityAndPointsAtTheOtherOne() {
        // ⚠️ **明确报错**而不是静默忽略：静默忽略的话调用方会以为改成功了。
        //    这就是"扩容单独成端点"的意义 —— 权限与审计都靠这个分界说得清。
        existing(10 * GI, "nfs-client");
        expansion("nfs-client", VolumeExpansion.CSI);

        PersistentVolumeClaimDTO body = new PersistentVolumeClaimDTO();
        body.setName("data");
        body.setTenantId("t1");
        body.setClusterId("c1");
        body.setNamespace("ns1");
        body.setStorage(BigDecimal.valueOf(20 * GI));

        assertThatThrownBy(() -> svc.update(body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("resize");
        verify(k8s, never()).update(any());
    }
}
