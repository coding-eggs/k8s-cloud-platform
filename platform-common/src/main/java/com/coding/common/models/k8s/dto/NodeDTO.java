package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 节点 DTO（集群级，无命名空间）。labels 继承自 {@link BaseResources}（供标签编辑整体回写）。
 * 容量/可分配量为基础单位 BigDecimal（CPU=核数、内存=字节），前端负责展示格式化与汇总。
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
    private BigDecimal cpuCapacity;
    private BigDecimal memoryCapacity;
    private BigDecimal cpuAllocatable;
    private BigDecimal memoryAllocatable;
    /** allocatable.pods（最大可调度 Pod 数） */
    private Long podsLimit;
    /** 节点上 Pod 数（运行时统计，仅查询返回） */
    private Long podCount;
    /** 节点上所有 Pod 的 CPU requests 合计（毫核，运行时统计，仅查询返回） */
    private Long cpuRequestMillicores;
    /** 节点上所有 Pod 的内存 requests 合计（字节，运行时统计，仅查询返回） */
    private Long memRequestBytes;
    private List<NodeConditionDTO> conditions;
    private List<NodeTaintDTO> taints;
    /** ISO-8601 创建时间 */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/nodes";
    }
}
