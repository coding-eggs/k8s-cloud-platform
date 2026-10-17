package com.coding.platformapi.components;

import java.util.List;

/**
 * 一条组件定义。{@code features} 是**依赖关系的唯一写法** —— 功能 → 组件的反查由
 * {@link ClusterComponentCatalog#componentsOf} 算出来，不手写第二张表。
 *
 * @param apiGroup API group 判据（读 {@code k8s_cluster.capability} 列，不另探）；可空
 * @param probes   工作负载判据；可空
 */
public record ComponentDef(String code, String name, String apiGroup,
                           List<ComponentProbe> probes, List<String> features) {
}
