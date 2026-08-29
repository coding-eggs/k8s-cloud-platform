package com.coding.k8score.operations;

import java.util.List;

/**
 * 命名空间级别的资源操作（如 Deployment、RoleBinding、ServiceAccount）
 */
public interface NamespacedOperations<T> extends ResourceOperations<T> {

    /**
     * 查询列表；labelSelector 为 K8s 原生标签选择器语法，原样透传，空则不加过滤
     */
    List<T> list(String namespace, String labelSelector);

    T get(String namespace, String name);

    void delete(String namespace, String name);

    String yaml(String namespace, String name);

}