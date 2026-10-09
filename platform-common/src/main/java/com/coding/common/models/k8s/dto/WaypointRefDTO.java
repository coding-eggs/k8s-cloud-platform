package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * waypoint 引用候选（窄投影：名字 + 它处理哪类流量）。
 *
 * <p>不是 K8s 资源，也不是 {@code BaseResources} 子类 —— 它是 Gateway 上的**派生视图**：名字取自
 * {@code metadata.name}，类型取自 label {@code istio.io/waypoint-for}（缺省 = {@code service}，见
 * {@code GatewayService.waypointForOf}）。
 *
 * <p><b>为什么调用方需要类型</b>：{@code istio.io/use-waypoint} 指向的 waypoint 必须能处理该处流量的
 * 目标类型，否则 istio <b>静默放行</b>（策略不生效且无任何报错）：
 * <ul>
 *   <li>命名空间级（东西向、目标是服务）→ 只能选 {@code service} 或 {@code all}</li>
 *   <li>Pod 级（目标是 Pod/VM IP）→ 只能选 {@code workload} 或 {@code all}</li>
 * </ul>
 * 所以候选必须以「名字 + 类型」成对下发，让选择器能按类型过滤并把不匹配的候选说明白，
 * 而不是给出一个必然不生效的选项。
 */
@Data
public class WaypointRefDTO {

    /** Gateway 名（= {@code istio.io/use-waypoint} 的取值） */
    private String name;

    /** 处理哪类流量：{@code service}（默认，label 缺省时）/ {@code workload} / {@code all} / {@code none} */
    private String waypointFor;

}
