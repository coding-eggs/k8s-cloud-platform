package com.coding.k8score.converter.impl.snapshot;

import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeSnapshotConverterTest {

    private final VolumeSnapshotConverter converter = new VolumeSnapshotConverter();

    private static GenericKubernetesResource snapshot(Map<String, Object> status) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("snapshot.storage.k8s.io/v1");
        res.setKind("VolumeSnapshot");
        res.setMetadata(new ObjectMetaBuilder().withName("snap").withNamespace("ns1")
                .withCreationTimestamp("2026-11-01T00:00:00Z").build());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("source", Map.of("persistentVolumeClaimName", "data"));
        spec.put("volumeSnapshotClassName", "cls");
        res.setAdditionalProperty("spec", spec);
        if (status != null) {
            res.setAdditionalProperty("status", status);
        }
        return res;
    }

    @Test
    void revertReadsStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("readyToUse", true);
        status.put("restoreSize", "1Gi");
        status.put("boundVolumeSnapshotContentName", "c1");
        status.put("creationTime", "2026-11-01T00:00:05Z");

        VolumeSnapshotDTO dto = converter.revert(snapshot(status));

        assertThat(dto.getName()).isEqualTo("snap");
        assertThat(dto.getNamespace()).isEqualTo("ns1");
        assertThat(dto.getSourcePvcName()).isEqualTo("data");
        assertThat(dto.getVolumeSnapshotClassName()).isEqualTo("cls");
        assertThat(dto.getReadyToUse()).isTrue();
        assertThat(dto.getRestoreSize()).isEqualByComparingTo(new BigDecimal("1073741824"));
        assertThat(dto.getBoundContentName()).isEqualTo("c1");
        assertThat(dto.getSnapshotTime()).isEqualTo("2026-11-01T00:00:05Z");
        assertThat(dto.getCreationTime()).isEqualTo("2026-11-01T00:00:00Z");
    }

    @Test
    void revertSurfacesError() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("error", Map.of("message", "boom", "time", "2026-11-01T00:01:00Z"));

        VolumeSnapshotDTO dto = converter.revert(snapshot(status));

        assertThat(dto.getErrorMessage()).isEqualTo("boom");
        assertThat(dto.getErrorTime()).isEqualTo("2026-11-01T00:01:00Z");
        assertThat(dto.getReadyToUse()).isNull();
    }

    @Test
    void revertWithoutStatus() {
        VolumeSnapshotDTO dto = converter.revert(snapshot(null));

        assertThat(dto.getReadyToUse()).isNull();
        assertThat(dto.getRestoreSize()).isNull();
        assertThat(dto.getErrorMessage()).isNull();
        assertThat(dto.getBoundContentName()).isNull();
        assertThat(dto.getSnapshotTime()).isNull();
    }

    @Test
    void revertReadsDeletionTimestamp() {
        GenericKubernetesResource res = snapshot(null);
        res.getMetadata().setDeletionTimestamp("2026-11-01T00:02:00Z");

        assertThat(converter.revert(res).getDeletionTimestamp()).isEqualTo("2026-11-01T00:02:00Z");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertWritesOnlySpec() {
        VolumeSnapshotDTO dto = new VolumeSnapshotDTO();
        dto.setName("snap");
        dto.setNamespace("ns1");
        dto.setSourcePvcName("data");
        dto.setVolumeSnapshotClassName("cls");
        dto.setReadyToUse(true);

        GenericKubernetesResource res = converter.convert(dto);

        assertThat(res.getApiVersion()).isEqualTo("snapshot.storage.k8s.io/v1");
        assertThat(res.getKind()).isEqualTo("VolumeSnapshot");
        Map<String, Object> spec = (Map<String, Object>) res.getAdditionalProperties().get("spec");
        assertThat((Map<String, Object>) spec.get("source"))
                .containsEntry("persistentVolumeClaimName", "data");
        assertThat(spec).containsEntry("volumeSnapshotClassName", "cls");
        assertThat(res.getAdditionalProperties()).doesNotContainKey("status");
    }
}
