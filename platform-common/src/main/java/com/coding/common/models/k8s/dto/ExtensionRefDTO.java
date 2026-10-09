package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * filter 的 {@code extensionRef}（Gateway API v1 的 LocalObjectReference：group/kind/name <b>三者都必填</b>）。
 * <p>指向实现私有的 filter CRD（如 Istio 的 AuthorizationPolicy 引用）。
 * <p>注意与既有的 {@link LocalObjectReferenceDTO} 区分：那个只有 {@code name}（core 组内引用），
 * 本类型是跨组的完整引用。HttpRoute / GrpcRoute 共用，故独立成顶层 DTO。
 */
@Data
public class ExtensionRefDTO {

    /** 目标 CRD 的 group（必填），如 gateway.networking.k8s.io */
    private String group;

    /** 目标 CRD 的 kind（必填），如 AuthorizationPolicy */
    private String kind;

    /** 目标对象名（必填） */
    private String name;

}
