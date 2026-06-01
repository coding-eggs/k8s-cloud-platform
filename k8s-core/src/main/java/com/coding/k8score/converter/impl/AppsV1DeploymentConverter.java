package com.coding.k8score.converter.impl;

import com.coding.k8score.converter.CommonConverter;
import com.coding.common.models.k8s.dto.DeploymentDTO;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;

/**
 * app/v1 版本的deployment dto转换器
 */
public class AppsV1DeploymentConverter implements CommonConverter<Deployment, DeploymentDTO> {

    @Override
    public Deployment convert(DeploymentDTO dto) {
        Deployment deployment = new Deployment();

        //metadata
        deployment.setMetadata(new ObjectMetaBuilder()
                        .withName(dto.getName())
                        .withNamespace(dto.getNamespace())
                        .withLabels(dto.getLabels())
                .build());

        //spec
        deployment.setSpec(new DeploymentSpecBuilder()
                        //replicas
                        .withReplicas(dto.getReplicas())
                        //label selector
                        .withSelector(new LabelSelectorBuilder()
                                .withMatchLabels(dto.getSelector())
                                .build())
                        //template
                        .withTemplate(new PodTemplateSpecBuilder()
                                .withMetadata(new ObjectMetaBuilder()
                                        .withLabels(dto.getLabels())
                                        .build())
                                .withSpec(new PodSpecBuilder()
                                        //containers
                                        .withContainers(dto.getContainers().stream().map(c ->
                                                        new ContainerBuilder()
                                                                //container name
                                                                .withName(c.getName())
                                                                //container image
                                                                .withImage(c.getImage())
                                                                //env
                                                                .withEnv(c.getEnvs().stream().map(e -> new EnvVarBuilder()
                                                                        .withName(e.getName())
                                                                        .withValue(e.getValue())
                                                                        .build()).toList())
                                                                .build()
                                        ).toList())
                                        .build())
                                .build())
                .build());
        return deployment;
    }

    @Override
    public DeploymentDTO revert(Deployment deployment) {



        return null;
    }
}
