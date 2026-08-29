package com.coding.common.models.k8s;

/**
 * 命名空间级资源 DTO 通用契约：向 controller 基类提供 name/namespace 访问器，
 * 用于 create/update 时从 body 提取 namespace 做分配表边界校验。
 * <p>
 * Lombok {@code @Data} 的同名字段自动生成 getter，直接 implements 即满足；
 * {@link BaseResources} 已实现，legacy DTO（SA/RoleBinding/ClusterRole）继承获得。
 */
public interface NamespacedResourceDTO {

    String getName();

    String getNamespace();
}
