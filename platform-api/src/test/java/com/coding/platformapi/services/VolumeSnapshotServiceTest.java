package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.k8s.dto.StorageClassProvisionerDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassOptionDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassOptionsDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.VolumeSnapshotClassClient;
import com.coding.platformapi.models.VolumeSnapshotCapability;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 卷快照的门控与创建校验（spec §5.3）。门控是活探测：探测失败要报错，不能当成「不支持」；
 * 创建在下发前把「PVC 不在 / 没绑定 / 存储类不支持 / 快照类选不出来」翻译成看得懂的话。
 */
class VolumeSnapshotServiceTest {

    private static final String NFS_CSI = "nfs.csi.k8s.io";
    private static final String NFS_SUBDIR = "cluster.local/nfs-subdir";

    private final K8sClient k8s = mock(K8sClient.class);
    private final VolumeSnapshotClassClient classClient = mock(VolumeSnapshotClassClient.class);
    private final StorageClassService storageClasses = mock(StorageClassService.class);
    private final VolumeSnapshotService svc = new VolumeSnapshotService(k8s, classClient, storageClasses);

    private static VolumeSnapshotClassOptionDTO cls(String name, String driver, boolean isDefault) {
        VolumeSnapshotClassOptionDTO c = new VolumeSnapshotClassOptionDTO();
        c.setName(name);
        c.setDriver(driver);
        c.setDeletionPolicy("Delete");
        c.setIsDefault(isDefault);
        return c;
    }

    private void classes(boolean crdInstalled, VolumeSnapshotClassOptionDTO... items) {
        VolumeSnapshotClassOptionsDTO out = new VolumeSnapshotClassOptionsDTO();
        out.setCrdInstalled(crdInstalled);
        out.setClasses(List.of(items));
        when(classClient.options("c1")).thenReturn(out);
    }

    private static StorageClassProvisionerDTO sc(String name, String provisioner) {
        StorageClassProvisionerDTO s = new StorageClassProvisionerDTO();
        s.setName(name);
        s.setProvisioner(provisioner);
        return s;
    }

    private void storageClasses(StorageClassProvisionerDTO... items) {
        when(storageClasses.provisioners("c1")).thenReturn(List.of(items));
    }

    private void pvc(String phase, String storageClass) {
        PersistentVolumeClaimDTO p = new PersistentVolumeClaimDTO();
        p.setName("data");
        p.setNamespace("ns1");
        p.setPhase(phase);
        p.setStorageClassName(storageClass);
        when(k8s.get(any(PersistentVolumeClaimDTO.class))).thenReturn(p);
    }

    private static VolumeSnapshotDTO request(String className) {
        VolumeSnapshotDTO d = new VolumeSnapshotDTO();
        d.setTenantId("t1");
        d.setClusterId("c1");
        d.setNamespace("ns1");
        d.setName("data-20261102000000");
        d.setSourcePvcName("data");
        d.setVolumeSnapshotClassName(className);
        return d;
    }

    private static VolumeSnapshotDTO query() {
        VolumeSnapshotDTO d = new VolumeSnapshotDTO();
        d.setClusterId("c1");
        return d;
    }

    private String createdClass() {
        ArgumentCaptor<VolumeSnapshotDTO> sent = ArgumentCaptor.forClass(VolumeSnapshotDTO.class);
        verify(k8s).create(sent.capture());
        return sent.getValue().getVolumeSnapshotClassName();
    }

    // ---- capability ----

    @Test
    void capabilityNoCrd() {
        classes(false);
        storageClasses();

        VolumeSnapshotCapability cap = svc.capability(query());

        assertThat(cap.available()).isFalse();
        assertThat(cap.reason()).isEqualTo("NO_CRD");
    }

    @Test
    void capabilityNoClass() {
        classes(true);
        storageClasses(sc("nfs-csi", NFS_CSI));

        VolumeSnapshotCapability cap = svc.capability(query());

        assertThat(cap.available()).isFalse();
        assertThat(cap.reason()).isEqualTo("NO_CLASS");
    }

    @Test
    void capabilityMapsStorageClassesByDriver() {
        classes(true, cls("c1", NFS_CSI, true));
        storageClasses(sc("a", NFS_CSI), sc("b", NFS_SUBDIR));

        VolumeSnapshotCapability cap = svc.capability(query());

        assertThat(cap.available()).isTrue();
        assertThat(cap.reason()).isNull();
        assertThat(cap.classes()).extracting(VolumeSnapshotClassOptionDTO::getName).containsExactly("c1");
        assertThat(cap.storageClasses()).hasSize(2);
        assertThat(cap.storageClasses().get(0).name()).isEqualTo("a");
        assertThat(cap.storageClasses().get(0).snapshotClasses()).containsExactly("c1");
        assertThat(cap.storageClasses().get(1).name()).isEqualTo("b");
        assertThat(cap.storageClasses().get(1).snapshotClasses()).isEmpty();
    }

    @Test
    void capabilityProbeFailurePropagates() {
        when(classClient.options("c1")).thenThrow(new CloudPlatformException(500, "k8s-server down"));

        assertThatThrownBy(() -> svc.capability(query()))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("k8s-server down");
    }

    // ---- create ----

    @Test
    void createRejectsMissingPvc() {
        when(k8s.get(any(PersistentVolumeClaimDTO.class))).thenReturn(null);

        assertThatThrownBy(() -> svc.create(request(null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("data");
        verify(k8s, never()).create(any());
    }

    @Test
    void createRejectsUnboundPvc() {
        pvc("Pending", "nfs-csi");

        assertThatThrownBy(() -> svc.create(request(null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("未绑定");
        verify(k8s, never()).create(any());
    }

    @Test
    void createRejectsPvcWithoutStorageClass() {
        pvc("Bound", null);
        classes(true, cls("c1", NFS_CSI, true));
        storageClasses(sc("nfs-csi", NFS_CSI));

        assertThatThrownBy(() -> svc.create(request(null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("该 PVC 所在的存储类不支持快照");
        verify(k8s, never()).create(any());
    }

    @Test
    void createRejectsStaticStorageClass() {
        pvc("Bound", "manual");
        classes(true, cls("c1", NFS_CSI, true));
        storageClasses(sc("nfs-csi", NFS_CSI));

        assertThatThrownBy(() -> svc.create(request(null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("该 PVC 所在的存储类不支持快照");
    }

    @Test
    void createRejectsDriverMismatch() {
        pvc("Bound", "nfs-subdir");
        classes(true, cls("c1", NFS_CSI, true));
        storageClasses(sc("nfs-subdir", NFS_SUBDIR));

        assertThatThrownBy(() -> svc.create(request("c1")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("该 PVC 所在的存储类不支持快照");
        verify(k8s, never()).create(any());
    }

    @Test
    void createPicksDefaultClass() {
        pvc("Bound", "nfs-csi");
        classes(true, cls("a", NFS_CSI, false), cls("b", NFS_CSI, true));
        storageClasses(sc("nfs-csi", NFS_CSI));

        svc.create(request(null));

        assertThat(createdClass()).isEqualTo("b");
    }

    @Test
    void createPicksOnlyMatch() {
        pvc("Bound", "nfs-csi");
        classes(true, cls("only", NFS_CSI, false), cls("other", "ebs.csi.aws.com", true));
        storageClasses(sc("nfs-csi", NFS_CSI));

        svc.create(request(null));

        assertThat(createdClass()).isEqualTo("only");
    }

    @Test
    void createRequiresChoiceWhenAmbiguous() {
        pvc("Bound", "nfs-csi");
        classes(true, cls("a", NFS_CSI, false), cls("b", NFS_CSI, false));
        storageClasses(sc("nfs-csi", NFS_CSI));

        assertThatThrownBy(() -> svc.create(request(null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("请选择快照类");
        verify(k8s, never()).create(any());
    }

    @Test
    void createKeepsExplicitMatchingClass() {
        pvc("Bound", "nfs-csi");
        classes(true, cls("a", NFS_CSI, false), cls("b", NFS_CSI, true));
        storageClasses(sc("nfs-csi", NFS_CSI));

        svc.create(request("a"));

        assertThat(createdClass()).isEqualTo("a");
    }
}
