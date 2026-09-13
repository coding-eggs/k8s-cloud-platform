package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * K8s ReplicaSet 资源定义（只读：平台侧仅用于按 labelSelector 列出 Deployment 名下的 RS，
 * 供工作负载指标把 pod-owner 从 Deployment 归并到 ReplicaSet）。不支持平台侧创建/更新/删除。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "K8s ReplicaSet 资源定义（只读）")
public class ReplicaSetDTO extends BaseResources {

    @Override
    public String getApiPath() {
        return "/resources/replicasets";
    }

}
