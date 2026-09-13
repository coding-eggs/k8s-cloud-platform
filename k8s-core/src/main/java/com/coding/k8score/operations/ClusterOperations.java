package com.coding.k8score.operations;

import java.util.List;

/**
 * 集群级别的资源操作（如 ClusterRole），不涉及命名空间参数
 */
public interface ClusterOperations<T> extends ResourceOperations<T> {

    /**
     * 查询列表；labelSelector 为 K8s 原生标签选择器语法，原样透传，空则不加过滤
     */
    List<T> list(String labelSelector, String fieldSelector);

    T get(String name);

    void delete(String name);

    String yaml(String name);
}