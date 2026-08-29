package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 工作负载统一 DTO（Deployment / StatefulSet / DaemonSet 共用基础表单字段）。
 * kind ∈ deployment | statefulset | daemonset（继承自 BaseResources，create/update 按它分发）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkloadDTO extends BaseResources {

    /** 期望副本数（DaemonSet 无此概念，为 null） */
    private Integer replicas;

    /** 就绪副本数（仅查询返回） */
    private Integer readyReplicas;

    /** 容器镜像列表（基础表单取全部容器 image） */
    private List<String> images;

    /** 容器端口（创建时可选，仅查询不返回） */
    private List<PortDTO> ports;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/workloads";
    }

}
