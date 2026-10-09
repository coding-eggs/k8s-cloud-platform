package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * Route → 后端引用（{@code rule.backendRefs[]} 项，Gateway API v1 的 BackendObjectReference + weight）。
 * <p>各 Route 类型共用，故独立成顶层 DTO。
 * <p>缺省语义：{@code group}=core、{@code kind}=Service、{@code namespace}=本 Route 所在命名空间。
 * 跨命名空间需在目标命名空间存在 ReferenceGrant。
 * <p>字段名对齐 CRD：权重字段是 {@code weight}，合法区间 <b>0–1000000</b>（CRD 上限；非百分比，
 * 实际占比 = weight / 本 rule 内全部 weight 之和，缺省 1）。
 */
@Data
public class BackendRefDTO {

    /** 后端资源 group；缺省 core（即 Service） */
    private String group;

    /** 后端资源 kind；缺省 Service */
    private String kind;

    /** 后端资源名（必填） */
    private String name;

    /** 后端资源所在命名空间；缺省 = 本 Route 所在命名空间 */
    private String namespace;

    /** 后端端口（Service 必填；指 Service 的 port 而非 targetPort） */
    private Integer port;

    /** 权重（0–1000000，缺省 1）。0 = 不向其转发 */
    private Integer weight;

}
