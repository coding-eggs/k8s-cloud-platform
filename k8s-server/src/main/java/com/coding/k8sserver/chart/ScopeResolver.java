package com.coding.k8sserver.chart;

/** 把渲染结果里的 {@code apiVersion + kind} 映射到作用域。纯函数，便于单测。 */
@FunctionalInterface
public interface ScopeResolver {
    Scope scopeOf(String apiVersion, String kind);
}
