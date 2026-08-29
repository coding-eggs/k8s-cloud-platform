package com.coding.k8score.operations;

/**
 * K8s 资源操作基类，提供通用能力（apiVersion、创建、更新）
 */
public interface ResourceOperations<T> {

    String apiVersion();

    T create(T resource);

    T update(T resource);

}