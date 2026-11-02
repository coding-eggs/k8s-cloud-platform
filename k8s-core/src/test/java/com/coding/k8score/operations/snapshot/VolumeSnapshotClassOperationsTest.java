package com.coding.k8score.operations.snapshot;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassDTO;
import com.coding.k8score.converter.impl.snapshot.VolumeSnapshotClassConverter;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class VolumeSnapshotClassOperationsTest {

    private final VolumeSnapshotClassConverter converter = new VolumeSnapshotClassConverter();

    private static GenericKubernetesResource snapshotClass(Map<String, String> annotations) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new ObjectMetaBuilder().withName("nfs-csi-snapclass").withAnnotations(annotations).build());
        res.setAdditionalProperty("driver", "nfs.csi.k8s.io");
        res.setAdditionalProperty("deletionPolicy", "Delete");
        res.setAdditionalProperty("parameters", Map.of("secret", "x"));
        return res;
    }

    @Test
    void classReadsDefaultAnnotation() {
        VolumeSnapshotClassDTO dto = converter.revert(snapshotClass(
                Map.of("snapshot.storage.kubernetes.io/is-default-class", "true")));

        assertThat(dto.getName()).isEqualTo("nfs-csi-snapclass");
        assertThat(dto.getDriver()).isEqualTo("nfs.csi.k8s.io");
        assertThat(dto.getDeletionPolicy()).isEqualTo("Delete");
        assertThat(dto.getParameters()).containsEntry("secret", "x");
        assertThat(dto.getIsDefault()).isTrue();
    }

    @Test
    void classWithoutAnnotationIsNotDefault() {
        assertThat(converter.revert(snapshotClass(null)).getIsDefault()).isFalse();
        assertThat(converter.revert(snapshotClass(
                Map.of("snapshot.storage.kubernetes.io/is-default-class", "false"))).getIsDefault()).isFalse();
    }

    @Test
    void classCreateIsRejected() {
        VolumeSnapshotClassOperations ops = new VolumeSnapshotClassOperations(mock(KubernetesClient.class), converter);

        assertNotSupported(() -> ops.create(new VolumeSnapshotClassDTO()));
        assertNotSupported(() -> ops.update(new VolumeSnapshotClassDTO()));
        assertNotSupported(() -> ops.delete("x"));
    }

    private static void assertNotSupported(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(CloudPlatformException.class)
                .satisfies(e -> assertThat(((CloudPlatformException) e).getCode())
                        .isEqualTo(EnumResponseType.OPERATION_NOT_SUPPORTED.getCode()));
    }
}
