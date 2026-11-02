package com.coding.k8score.converter.impl.snapshot;

import com.coding.common.models.k8s.dto.VolumeSnapshotContentDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import java.math.BigDecimal;
import java.util.Map;

/**
 * GenericKubernetesResource → VolumeSnapshotContentDTO（只读，没有 convert）。
 * <p>注意 status.restoreSize 在这里是整数字节，不是 VolumeSnapshot 上那种 Quantity 串。
 */
public class VolumeSnapshotContentConverter {

    public VolumeSnapshotContentDTO revert(GenericKubernetesResource res) {
        VolumeSnapshotContentDTO dto = new VolumeSnapshotContentDTO();
        if (res.getMetadata() != null) {
            dto.setName(res.getMetadata().getName());
            dto.setLabels(res.getMetadata().getLabels());
            dto.setCreationTime(res.getMetadata().getCreationTimestamp());
        }
        Map<String, Object> props = res.getAdditionalProperties();
        if (props == null) {
            return dto;
        }
        if (props.get("spec") instanceof Map<?, ?> spec) {
            dto.setDriver(asStr(spec.get("driver")));
            dto.setDeletionPolicy(asStr(spec.get("deletionPolicy")));
            dto.setVolumeSnapshotClassName(asStr(spec.get("volumeSnapshotClassName")));
            if (spec.get("volumeSnapshotRef") instanceof Map<?, ?> ref) {
                dto.setSnapshotName(asStr(ref.get("name")));
                dto.setSnapshotNamespace(asStr(ref.get("namespace")));
            }
        }
        if (props.get("status") instanceof Map<?, ?> status) {
            if (status.get("readyToUse") instanceof Boolean ready) {
                dto.setReadyToUse(ready);
            }
            if (status.get("restoreSize") instanceof Number n) {
                dto.setRestoreSize(BigDecimal.valueOf(n.longValue()));
            }
            dto.setSnapshotHandle(asStr(status.get("snapshotHandle")));
        }
        return dto;
    }

    private static String asStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

}
