package com.coding.k8sserver.chart;

/** 一个 kind 是否受命名空间约束。UNKNOWN 表示无法判定（调用方必须 fail-closed）。 */
public enum Scope {
    NAMESPACED, CLUSTER, UNKNOWN
}
