package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
public class DeploymentDTO extends BaseResources {

    private Integer replicas;

    private List<ContainerDTO> containers;

    // 简化 selector（不要暴露 matchExpressions）
    private Map<String, String> selector;

}
