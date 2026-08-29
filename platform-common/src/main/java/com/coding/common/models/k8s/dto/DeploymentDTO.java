package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "K8s Deployment 资源定义")
public class DeploymentDTO extends BaseResources {

    @Schema(description = "副本数量", example = "3")
    private Integer replicas;

    @Schema(description = "容器列表")
    private List<ContainerDTO> containers;

    // 简化 selector（不要暴露 matchExpressions）
    @Schema(description = "标签选择器，用于匹配 Pod")
    private Map<String, String> selector;

    @Override
    public String getApiPath() {
        return null; // 无独立 HTTP 端点（走 /resources/workloads / k8s-core 内部）
    }

}
