package com.coding.k8score.converter.impl.snapshot;

import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import com.coding.k8score.util.QuantityUtil;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VolumeSnapshotDTO ⇄ GenericKubernetesResource（snapshot.storage.k8s.io/v1）。
 * <p>convert 只写 spec（来源 PVC + 快照类），status 由 snapshot-controller 维护。
 */
public class VolumeSnapshotConverter {

    public static final String API_VERSION = "snapshot.storage.k8s.io/v1";

    public GenericKubernetesResource convert(VolumeSnapshotDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind("VolumeSnapshot");
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("source", Map.of("persistentVolumeClaimName", dto.getSourcePvcName()));
        if (notBlank(dto.getVolumeSnapshotClassName())) {
            spec.put("volumeSnapshotClassName", dto.getVolumeSnapshotClassName());
        }
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @SuppressWarnings("unchecked")
    public VolumeSnapshotDTO revert(GenericKubernetesResource res) {
        VolumeSnapshotDTO dto = new VolumeSnapshotDTO();
        if (res.getMetadata() != null) {
            dto.setName(res.getMetadata().getName());
            dto.setNamespace(res.getMetadata().getNamespace());
            dto.setLabels(res.getMetadata().getLabels());
            dto.setCreationTime(res.getMetadata().getCreationTimestamp());
            dto.setDeletionTimestamp(res.getMetadata().getDeletionTimestamp());
        }
        Map<String, Object> props = res.getAdditionalProperties();
        if (props == null) {
            return dto;
        }
        if (props.get("spec") instanceof Map<?, ?> spec) {
            if (spec.get("source") instanceof Map<?, ?> source) {
                dto.setSourcePvcName(asStr(source.get("persistentVolumeClaimName")));
            }
            dto.setVolumeSnapshotClassName(asStr(spec.get("volumeSnapshotClassName")));
        }
        if (props.get("status") instanceof Map<?, ?> status) {
            if (status.get("readyToUse") instanceof Boolean ready) {
                dto.setReadyToUse(ready);
            }
            Object size = status.get("restoreSize");
            if (size != null) {
                dto.setRestoreSize(QuantityUtil.toBase(new Quantity(String.valueOf(size))));
            }
            dto.setBoundContentName(asStr(status.get("boundVolumeSnapshotContentName")));
            dto.setSnapshotTime(asStr(status.get("creationTime")));
            if (status.get("error") instanceof Map<?, ?> error) {
                dto.setErrorMessage(asStr(error.get("message")));
                dto.setErrorTime(asStr(error.get("time")));
            }
        }
        return dto;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String asStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

}
