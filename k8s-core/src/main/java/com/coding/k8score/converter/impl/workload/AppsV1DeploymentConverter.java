package com.coding.k8score.converter.impl.workload;

import com.coding.common.models.k8s.dto.ContainerDTO;
import com.coding.common.models.k8s.dto.EnvDTO;
import com.coding.common.models.k8s.dto.PortDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.common.models.k8s.dto.DeploymentDTO;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * app/v1 版本的deployment dto转换器
 */
public class AppsV1DeploymentConverter implements CommonConverter<Deployment, DeploymentDTO> {

    @Override
    public Deployment convert(DeploymentDTO dto) {
        Deployment deployment = new Deployment();

        deployment.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        deployment.setSpec(new io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder()
                .withReplicas(dto.getReplicas())
                .withSelector(new LabelSelectorBuilder()
                        .withMatchLabels(dto.getSelector())
                        .build())
                .withTemplate(new PodTemplateSpecBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withLabels(dto.getLabels())
                                .build())
                        .withSpec(new PodSpecBuilder()
                                .withContainers(dto.getContainers().stream().map(c ->
                                        new ContainerBuilder()
                                                .withName(c.getName())
                                                .withImage(c.getImage())
                                                .withEnv(c.getEnvs() != null ? c.getEnvs().stream().map(e ->
                                                        new EnvVarBuilder()
                                                                .withName(e.getName())
                                                                .withValue(e.getValue())
                                                                .build()).toList() : Collections.emptyList())
                                                .withPorts(c.getPorts() != null ? c.getPorts().stream().map(p ->
                                                        new ContainerPortBuilder()
                                                                .withContainerPort(p.getContainerPort())
                                                                .build()).toList() : Collections.emptyList())
                                                .build()
                                ).toList())
                                .build())
                        .build())
                .build());

        return deployment;
    }

    @Override
    public DeploymentDTO revert(Deployment deployment) {
        if (deployment == null) return null;

        DeploymentDTO dto = new DeploymentDTO();

        var metadata = deployment.getMetadata();
        if (metadata != null) {
            dto.setName(metadata.getName());
            dto.setNamespace(metadata.getNamespace());
            dto.setLabels(metadata.getLabels() != null ? Map.copyOf(metadata.getLabels()) : Map.of());
        }

        var spec = deployment.getSpec();
        if (spec != null) {
            dto.setReplicas(spec.getReplicas());

            var selector = spec.getSelector();
            if (selector != null && selector.getMatchLabels() != null) {
                dto.setSelector(Map.copyOf(selector.getMatchLabels()));
            } else {
                dto.setSelector(Map.of());
            }

            var template = spec.getTemplate();
            if (template != null && template.getSpec() != null) {
                List<Container> containers = template.getSpec().getContainers();
                if (containers != null && !containers.isEmpty()) {
                    dto.setContainers(containers.stream().map(this::toContainerDTO).toList());
                } else {
                    dto.setContainers(Collections.emptyList());
                }
            } else {
                dto.setContainers(Collections.emptyList());
            }
        }

        return dto;
    }

    private ContainerDTO toContainerDTO(Container c) {
        ContainerDTO cdto = new ContainerDTO();
        cdto.setName(c.getName());
        cdto.setImage(c.getImage());
        if (c.getEnv() != null && !c.getEnv().isEmpty()) {
            List<EnvDTO> envs = c.getEnv().stream().map(e -> {
                EnvDTO edto = new EnvDTO();
                edto.setName(e.getName());
                edto.setValue(e.getValue());
                return edto;
            }).collect(Collectors.toList());
            cdto.setEnvs(envs);
        }
        if (c.getPorts() != null && !c.getPorts().isEmpty()) {
            List<PortDTO> ports = c.getPorts().stream().map(p -> {
                PortDTO pdto = new PortDTO();
                pdto.setContainerPort(p.getContainerPort());
                return pdto;
            }).collect(Collectors.toList());
            cdto.setPorts(ports);
        }
        return cdto;
    }
}
