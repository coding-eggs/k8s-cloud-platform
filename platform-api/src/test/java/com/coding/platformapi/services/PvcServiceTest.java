package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.platformapi.k8s.K8sClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PVC 扩容的两条规则（S4.1）：**只增不减** + **存储类得开了在线扩容**。
 *
 * <p>为什么这两条必须有：K8s 对 PVC 的 `spec` 是**不可变**的，唯一允许的例外就是"往大改容量"，
 * 而且还得存储类开了 `allowVolumeExpansion`。不透传校验的话，用户填个更小的数会拿到一句
 * 看不懂的 K8s 原话，填个更大的数而存储类没开则会在 SSA 那一步失败。
 */
class PvcServiceTest {

    private static final long GI = 1024L * 1024 * 1024;

    private final K8sClient k8s = mock(K8sClient.class);
    private final StorageClassService storageClasses = mock(StorageClassService.class);
    private final PvcService svc = new PvcService(k8s, storageClasses);

    /** 集群上**现在**的这个 PVC（旧容量 + 它用的存储类）。 */
    private PersistentVolumeClaimDTO existing(long bytes, String storageClass) {
        PersistentVolumeClaimDTO old = new PersistentVolumeClaimDTO();
        old.setName("data");
        old.setNamespace("ns1");
        old.setStorageClassName(storageClass);
        old.setAccessModes(java.util.List.of("ReadWriteOnce"));
        old.setStorage(BigDecimal.valueOf(bytes));
        when(k8s.get(any())).thenReturn(old);
        return old;
    }

    private void storageClassSupportsExpansion(String name, boolean allowed) {
        StorageClassDTO sc = new StorageClassDTO();
        sc.setName(name);
        sc.setAllowVolumeExpansion(allowed);
        when(storageClasses.get(name, "t1", "c1")).thenReturn(sc);
    }

    private static PersistentVolumeClaimDTO resizeTo(long bytes) {
        PersistentVolumeClaimDTO body = new PersistentVolumeClaimDTO();
        body.setName("data");
        body.setTenantId("t1");
        body.setClusterId("c1");
        body.setNamespace("ns1");
        body.setStorageClassName("nfs-client");
        body.setStorage(BigDecimal.valueOf(bytes));
        return body;
    }

    @Test
    void aGenuineIncreaseGoesThrough() {
        existing(10 * GI, "nfs-client");
        storageClassSupportsExpansion("nfs-client", true);
        when(k8s.update(any())).thenReturn(resizeTo(20 * GI));

        svc.update(resizeTo(20 * GI));

        verify(k8s).update(any());
    }

    @Test
    void shrinkingIsRefusedWithBothNumbersInPlainSight() {
        // K8s 根本不支持缩容 —— 这句要我们自己说，别把它的原话甩给用户
        existing(10 * GI, "nfs-client");
        storageClassSupportsExpansion("nfs-client", true);

        assertThatThrownBy(() -> svc.update(resizeTo(5 * GI)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("10Gi")
                .hasMessageContaining("5Gi");
        verify(k8s, never()).update(any());
    }

    @Test
    void theSameSizeIsRefusedTooAndNeverTouchesTheCluster() {
        // 相等 = 什么都没改。放过去就是白跑一次 SSA，还会在集群上留一条事件
        existing(10 * GI, "nfs-client");
        storageClassSupportsExpansion("nfs-client", true);

        assertThatThrownBy(() -> svc.update(resizeTo(10 * GI)))
                .isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).update(any());
    }

    @Test
    void aStorageClassWithoutOnlineExpansionIsRefused() {
        existing(10 * GI, "local-path");
        storageClassSupportsExpansion("local-path", false);

        assertThatThrownBy(() -> svc.update(resizeTo(20 * GI)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("local-path")
                .hasMessageContaining("allowVolumeExpansion");
        verify(k8s, never()).update(any());
    }

    @Test
    void anUnreadableStorageClassIsRefusedRatherThanAssumedToBeFine() {
        // ⚠️ fail-closed：读不到就不敢说"能扩"。放过去的话，用户在 SSA 那一步拿到的
        //    会是一句 K8s 原话，比这里明确拒掉差得多
        existing(10 * GI, "gone");
        when(storageClasses.get("gone", "t1", "c1")).thenReturn(null);

        assertThatThrownBy(() -> svc.update(resizeTo(20 * GI)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("gone");
        verify(k8s, never()).update(any());
    }

    @Test
    void aResizeWithoutANewSizeIsRefused() {
        existing(10 * GI, "nfs-client");

        PersistentVolumeClaimDTO body = resizeTo(0);
        body.setStorage(null);
        assertThatThrownBy(() -> svc.update(body)).isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).update(any());
    }

    @Test
    void theUpdateSendsBackWhatTheClusterHasNotOnlyWhatTheRequestCarries() {
        // ⚠️ SSA 是会**删字段**的，而 PVC 的 `accessModes` 是必填、`storageClassName` 不可变 ——
        //    前端要是只带一个 storage 上来（"我就改个容量"），直接 apply 请求体会被 API server 拒。
        //    所以以**读回来的那一份**为底，只盖容量。
        existing(10 * GI, "nfs-client");
        storageClassSupportsExpansion("nfs-client", true);
        when(k8s.update(any())).thenAnswer(inv -> inv.getArgument(0));

        svc.update(resizeTo(20 * GI));   // 请求体里**只有** storage，别的什么都没有

        ArgumentCaptor<PersistentVolumeClaimDTO> sent =
                ArgumentCaptor.forClass(PersistentVolumeClaimDTO.class);
        verify(k8s).update(sent.capture());
        assertThat(sent.getValue().getAccessModes()).containsExactly("ReadWriteOnce");
        assertThat(sent.getValue().getStorageClassName()).isEqualTo("nfs-client");
        assertThat(sent.getValue().getStorage()).isEqualByComparingTo(BigDecimal.valueOf(20 * GI));
    }

    @Test
    void theStorageClassIsReadFromWhatTheClusterActuallyHasNotFromTheRequest() {
        // 请求里写的存储类可能是旧的/被改过的 —— 以集群上那个对象为准，否则门控会被绕过
        existing(10 * GI, "nfs-client");
        storageClassSupportsExpansion("nfs-client", true);
        when(k8s.update(any())).thenReturn(resizeTo(20 * GI));

        PersistentVolumeClaimDTO body = resizeTo(20 * GI);
        body.setStorageClassName("local-path");   // 请求里撒谎
        svc.update(body);

        verify(storageClasses).get("nfs-client", "t1", "c1");
    }
}
