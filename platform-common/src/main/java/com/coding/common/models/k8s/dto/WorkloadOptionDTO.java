package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 工作负载下拉候选项：Deployment / StatefulSet 的 namespace + kind + name */
@Data
public class WorkloadOptionDTO {

    private String namespace;

    /** Deployment / StatefulSet */
    private String kind;

    private String name;

}
