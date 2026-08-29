package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;

import java.util.List;

/**
 * Pod ⇄ PodDTO（只读：列表/查询返回；Pod 不支持平台侧创建/更新）
 */
public class CoreV1PodConverter implements CommonConverter<Pod, PodDTO> {

    @Override
    public Pod convert(PodDTO in) {
        throw new UnsupportedOperationException("Pod 不支持平台侧创建");
    }

    @Override
    public PodDTO revert(Pod pod) {
        PodDTO dto = new PodDTO();
        if (pod.getMetadata() != null) {
            dto.setName(pod.getMetadata().getName());
            dto.setNamespace(pod.getMetadata().getNamespace());
            dto.setLabels(pod.getMetadata().getLabels());
            if (pod.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(pod.getMetadata().getCreationTimestamp().toString());
            }
        }
        if (pod.getSpec() != null) {
            dto.setNodeName(pod.getSpec().getNodeName());
            if (pod.getSpec().getContainers() != null) {
                dto.setContainers(pod.getSpec().getContainers().stream()
                        .map(c -> c.getName())
                        .toList());
            }
        }
        if (pod.getStatus() != null) {
            dto.setPhase(pod.getStatus().getPhase());
            dto.setPodIp(pod.getStatus().getPodIP());
            int restarts = 0;
            if (pod.getStatus().getContainerStatuses() != null) {
                for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                    if (cs.getRestartCount() != null) {
                        restarts += cs.getRestartCount();
                    }
                }
            }
            dto.setRestarts(restarts);
        }
        return dto;
    }

}
