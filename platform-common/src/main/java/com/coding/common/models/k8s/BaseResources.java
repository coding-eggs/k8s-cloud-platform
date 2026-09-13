package com.coding.common.models.k8s;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 资源 DTO 统一基类 = 统一请求载体：身份字段（clusterId/tenantId/name/namespace）+ list 查询条件。
 * 子类必须覆写 {@link #getApiPath()} 声明 k8s-server 上的固定端点路径
 * （api 侧通用 client 的路径约定唯一来源；无独立 HTTP 端点的资源返回 null）。
 */
@Data
public abstract class BaseResources implements NamespacedResourceDTO {

    @Schema(name = "集群id")
    private String clusterId;

    @Schema(name = "租户id")
    private String tenantId;

    @Schema(name = "kind")
    private String kind;

    @Schema(name = "名称")
    private String name;

    @Schema(name = "命名空间")
    private String namespace;

    private Map<String, Object> annotations;

    @Schema(name = "labels")
    private Map<String, String> labels;

    @Schema(name = "通过字段筛选")
    private String fieldSelector;

    /** list 查询条件（仅 list 生效，其余操作忽略）：K8s 原生标签选择器语法，原样透传 */
    @Schema(name = "标签选择器（仅 list）")
    private String labelSelector;

    /**k8s-server 固定端点路径（如 /resources/pods）；无独立 HTTP 端点返回 null */
    public abstract String getApiPath();

}
