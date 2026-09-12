package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 节点 DTO（集群级，无命名空间）。labels 继承自 {@link BaseResources}（供标签编辑整体回写）。
 * 容量/可分配量保留原始 K8s 量字符串（如 "8"、"15Gi"、"7920m"），前端直接展示。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NodeDTO extends BaseResources {

    /** Ready 条件状态：True=Ready / False=NotReady / Unknown */
    private String status;
    /** spec.unschedulable（cordon 状态） */
    private Boolean unschedulable;
    /** node-role.kubernetes.io/* 的 key 后缀（control-plane/worker/...） */
    private List<String> roles;
    private String kubeletVersion;
    private String os;
    private String arch;
    private String kernelVersion;
    private String containerRuntimeVersion;
    private String osImage;
    private String podCidr;
    private String internalIp;
    private String externalIp;
    private String cpuCapacity;
    private String memoryCapacity;
    private String cpuAllocatable;
    private String memoryAllocatable;
    /** allocatable.pods（最大可调度 Pod 数） */
    private Long podsLimit;
    private List<NodeConditionDTO> conditions;
    private List<NodeTaintDTO> taints;
    /** ISO-8601 创建时间 */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/nodes";
    }
}
