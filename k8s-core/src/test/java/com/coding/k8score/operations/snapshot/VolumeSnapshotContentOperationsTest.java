package com.coding.k8score.operations.snapshot;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.VolumeSnapshotContentDTO;
import com.coding.k8score.converter.impl.snapshot.VolumeSnapshotContentConverter;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class VolumeSnapshotContentOperationsTest {

    private final VolumeSnapshotContentConverter converter = new VolumeSnapshotContentConverter();

    @Test
    void contentReadsSnapshotRef() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new ObjectMetaBuilder().withName("snapcontent-1").build());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("driver", "nfs.csi.k8s.io");
        spec.put("deletionPolicy", "Retain");
        spec.put("volumeSnapshotClassName", "nfs-csi-snapclass");
        spec.put("volumeSnapshotRef", Map.of("name", "snap", "namespace", "ns1"));
        res.setAdditionalProperty("spec", spec);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("readyToUse", true);
        // status.restoreSize 在 VolumeSnapshotContent 上是整数字节，不是 Quantity 串
        status.put("restoreSize", 1073741824L);
        status.put("snapshotHandle", "nfs-server#share#snap");
        res.setAdditionalProperty("status", status);

        VolumeSnapshotContentDTO dto = converter.revert(res);

        assertThat(dto.getName()).isEqualTo("snapcontent-1");
        assertThat(dto.getDriver()).isEqualTo("nfs.csi.k8s.io");
        assertThat(dto.getDeletionPolicy()).isEqualTo("Retain");
        assertThat(dto.getVolumeSnapshotClassName()).isEqualTo("nfs-csi-snapclass");
        assertThat(dto.getSnapshotName()).isEqualTo("snap");
        assertThat(dto.getSnapshotNamespace()).isEqualTo("ns1");
        assertThat(dto.getReadyToUse()).isTrue();
        assertThat(dto.getRestoreSize()).isEqualByComparingTo(new BigDecimal("1073741824"));
        assertThat(dto.getSnapshotHandle()).isEqualTo("nfs-server#share#snap");
        assertThat(dto.getOrphan()).isNull();
    }

    @Test
    void contentWithoutStatus() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new ObjectMetaBuilder().withName("c").build());

        VolumeSnapshotContentDTO dto = converter.revert(res);

        assertThat(dto.getReadyToUse()).isNull();
        assertThat(dto.getRestoreSize()).isNull();
    }

    @Test
    void contentDeleteIsRejected() {
        VolumeSnapshotContentOperations ops =
                new VolumeSnapshotContentOperations(mock(KubernetesClient.class), converter);

        assertThatThrownBy(() -> ops.delete("c"))
                .isInstanceOf(CloudPlatformException.class)
                .satisfies(e -> assertThat(((CloudPlatformException) e).getCode())
                        .isEqualTo(EnumResponseType.OPERATION_NOT_SUPPORTED.getCode()));
        assertThatThrownBy(() -> ops.create(new VolumeSnapshotContentDTO()))
                .isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> ops.update(new VolumeSnapshotContentDTO()))
                .isInstanceOf(CloudPlatformException.class);
    }
}
