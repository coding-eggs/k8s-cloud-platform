package com.coding.k8score.converter.impl.snapshot;

import com.coding.common.models.k8s.dto.VolumeSnapshotClassDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GenericKubernetesResource → VolumeSnapshotClassDTO（只读，没有 convert）。
 * <p>VolumeSnapshotClass 没有 spec：driver / deletionPolicy / parameters 都在顶层。
 */
public class VolumeSnapshotClassConverter {

    public static final String DEFAULT_ANNOTATION = "snapshot.storage.kubernetes.io/is-default-class";

    public VolumeSnapshotClassDTO revert(GenericKubernetesResource res) {
        VolumeSnapshotClassDTO dto = new VolumeSnapshotClassDTO();
        boolean isDefault = false;
        if (res.getMetadata() != null) {
            dto.setName(res.getMetadata().getName());
            dto.setLabels(res.getMetadata().getLabels());
            dto.setCreationTime(res.getMetadata().getCreationTimestamp());
            Map<String, String> annotations = res.getMetadata().getAnnotations();
            isDefault = annotations != null && "true".equals(annotations.get(DEFAULT_ANNOTATION));
        }
        dto.setIsDefault(isDefault);
        Map<String, Object> props = res.getAdditionalProperties();
        if (props != null) {
            dto.setDriver(asStr(props.get("driver")));
            dto.setDeletionPolicy(asStr(props.get("deletionPolicy")));
            if (props.get("parameters") instanceof Map<?, ?> params) {
                Map<String, String> out = new LinkedHashMap<>();
                params.forEach((k, v) -> out.put(String.valueOf(k), asStr(v)));
                dto.setParameters(out);
            }
        }
        return dto;
    }

    private static String asStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

}
