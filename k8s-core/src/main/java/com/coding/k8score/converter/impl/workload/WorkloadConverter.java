package com.coding.k8score.converter.impl.workload;

import com.coding.common.models.k8s.dto.PortDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WorkloadDTO ⇄ Deployment / StatefulSet / DaemonSet（一个 DTO 三种 kind）。
 * 基础表单字段：kind/name/namespace/labels/replicas/images/ports。
 */
public class WorkloadConverter {

    public static final String KIND_DEPLOYMENT = "deployment";
    public static final String KIND_STATEFULSET = "statefulset";
    public static final String KIND_DAEMONSET = "daemonset";

    // ==================== revert（K8s 对象 → DTO） ====================

    public WorkloadDTO revert(Deployment d) {
        WorkloadDTO dto = base(d.getMetadata(), KIND_DEPLOYMENT, d.getSpec().getTemplate());
        if (d.getSpec() != null) {
            dto.setReplicas(d.getSpec().getReplicas());
        }
        if (d.getStatus() != null) {
            dto.setReadyReplicas(d.getStatus().getReadyReplicas());
        }
        return dto;
    }

    public WorkloadDTO revert(StatefulSet s) {
        WorkloadDTO dto = base(s.getMetadata(), KIND_STATEFULSET, s.getSpec().getTemplate());
        if (s.getSpec() != null) {
            dto.setReplicas(s.getSpec().getReplicas());
        }
        if (s.getStatus() != null) {
            dto.setReadyReplicas(s.getStatus().getReadyReplicas());
        }
        return dto;
    }

    public WorkloadDTO revert(DaemonSet ds) {
        WorkloadDTO dto = base(ds.getMetadata(), KIND_DAEMONSET, ds.getSpec().getTemplate());
        //DaemonSet 无 replicas 概念
        if (ds.getStatus() != null && ds.getStatus().getNumberReady() != null) {
            dto.setReadyReplicas(ds.getStatus().getNumberReady());
        }
        return dto;
    }

    private WorkloadDTO base(io.fabric8.kubernetes.api.model.ObjectMeta meta, String kind, PodTemplateSpec template) {
        WorkloadDTO dto = new WorkloadDTO();
        if (meta != null) {
            dto.setName(meta.getName());
            dto.setNamespace(meta.getNamespace());
            dto.setLabels(meta.getLabels());
            if (meta.getCreationTimestamp() != null) {
                dto.setCreationTime(meta.getCreationTimestamp().toString());
            }
        }
        dto.setKind(kind);
        if (template != null && template.getSpec() != null && template.getSpec().getContainers() != null) {
            List<String> images = new ArrayList<>();
            for (Container c : template.getSpec().getContainers()) {
                images.add(c.getImage());
            }
            dto.setImages(images);
        }
        return dto;
    }

    // ==================== convert（DTO → K8s 对象，按 kind 分发） ====================

    public Deployment convertDeployment(WorkloadDTO dto) {
        return new DeploymentBuilder()
                .withNewMetadata()
                    .withName(dto.getName())
                    .withNamespace(dto.getNamespace())
                    .withLabels(effectiveLabels(dto))
                .endMetadata()
                .withNewSpec()
                    .withReplicas(dto.getReplicas() != null ? dto.getReplicas() : 1)
                    .withSelector(new LabelSelectorBuilder().withMatchLabels(selectorLabels(dto)).build())
                    .withTemplate(buildTemplate(dto))
                .endSpec()
                .build();
    }

    public StatefulSet convertStatefulSet(WorkloadDTO dto) {
        return new StatefulSetBuilder()
                .withNewMetadata()
                    .withName(dto.getName())
                    .withNamespace(dto.getNamespace())
                    .withLabels(effectiveLabels(dto))
                .endMetadata()
                .withNewSpec()
                    .withReplicas(dto.getReplicas() != null ? dto.getReplicas() : 1)
                    //headless service 缺省同名，基础表单不单独暴露
                    .withServiceName(dto.getName())
                    .withSelector(new LabelSelectorBuilder().withMatchLabels(selectorLabels(dto)).build())
                    .withTemplate(buildTemplate(dto))
                .endSpec()
                .build();
    }

    public DaemonSet convertDaemonSet(WorkloadDTO dto) {
        return new DaemonSetBuilder()
                .withNewMetadata()
                    .withName(dto.getName())
                    .withNamespace(dto.getNamespace())
                    .withLabels(effectiveLabels(dto))
                .endMetadata()
                .withNewSpec()
                    .withSelector(new LabelSelectorBuilder().withMatchLabels(selectorLabels(dto)).build())
                    .withTemplate(buildTemplate(dto))
                .endSpec()
                .build();
    }

    /**selector 固定 app=name，保证是模板标签的子集 */
    private Map<String, String> selectorLabels(WorkloadDTO dto) {
        return Map.of("app", dto.getName());
    }

    /**模板标签 = 用户标签 + app=name（覆盖同名键，保证 selector 可匹配） */
    private Map<String, String> effectiveLabels(WorkloadDTO dto) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (dto.getLabels() != null) {
            labels.putAll(dto.getLabels());
        }
        labels.put("app", dto.getName());
        return labels;
    }

    private PodTemplateSpec buildTemplate(WorkloadDTO dto) {
        List<String> images = dto.getImages() == null || dto.getImages().isEmpty()
                ? List.of("nginx:latest") : dto.getImages();
        List<Container> containers = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            ContainerBuilder cb = new ContainerBuilder()
                    .withName(images.size() == 1 ? "main" : "main-" + i)
                    .withImage(images.get(i));
            //端口只挂到第一个容器（基础表单约定）
            if (i == 0 && dto.getPorts() != null && !dto.getPorts().isEmpty()) {
                List<ContainerPort> ports = new ArrayList<>();
                for (PortDTO p : dto.getPorts()) {
                    if (p.getContainerPort() != null) {
                        ports.add(new ContainerPortBuilder().withContainerPort(p.getContainerPort()).build());
                    }
                }
                cb.withPorts(ports);
            }
            containers.add(cb.build());
        }
        return new PodTemplateSpecBuilder()
                .withNewMetadata()
                    .withLabels(effectiveLabels(dto))
                .endMetadata()
                .withNewSpec()
                    .withContainers(containers)
                .endSpec()
                .build();
    }

}
