package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 一条工作负载探测结果。{@code error} 非空 = 探测失败（消费方据此判 UNKNOWN，不能说成"未安装"）。
 */
@Data
public class WorkloadProbeResultDTO {
    private boolean found;
    private String namespace;
    private String kind;
    /** 命中的工作负载名 */
    private String name;
    /** app.kubernetes.io/version 标签优先，否则 image tag 原文（已剥 digest）；都没有 → null */
    private String version;
    private Integer ready;
    private Integer desired;
    /** 人读的一句话："3/3 就绪" / "命中 2 个（a, b），取第一个" */
    private String detail;
    /** 非空 = 这次探测失败（集群不可达 / 无权限），与 found=false 是两件事 */
    private String error;
}
