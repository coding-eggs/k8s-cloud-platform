package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 路由父引用（{@code parentRefs}）的 Gateway 候选 —— **窄投影**。
 *
 * <p>只带编辑那一个下拉要用的字段：能选哪个（name / namespace）、段名候选（listenerNames）、
 * 以及要不要标成 waypoint。
 *
 * <p>⚠️ 为什么不复用 {@link GatewayDTO}：这份数据会同时包含**平台代管命名空间**
 * （{@code istio-system}）里的网关，而租户对那个 ns 只有"能引用"这一件事可做。
 * 给全量 DTO 等于顺手把 listeners 的 TLS 证书引用、status、addresses 一起交出去
 * （见 spec §5.1 的四条规范）。
 */
@Data
@Schema(description = "路由父引用候选（窄投影：name/namespace/listenerNames/waypointFor）")
public class ParentGatewayRefDTO {

    @Schema(description = "Gateway 名（parentRef.name）")
    private String name;

    @Schema(description = "所在命名空间（parentRef.namespace）")
    private String namespace;

    @Schema(description = "监听器名（去重保序）—— parentRef.sectionName 的候选；空 = 该网关没有具名监听器")
    private List<String> listenerNames;

    @Schema(description = "istio.io/waypoint-for 标签值（无则 null）—— 前端据此标 waypoint 后缀")
    private String waypointFor;
}
