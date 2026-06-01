package com.coding.common.models.k8s;

import com.coding.common.models.k8s.dto.DeploymentDTO;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum ResourceType {

    DEPLOYMENT(DeploymentDTO.class),

    ;
    private final Class<?> clazz;

}
