package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务网格拓扑：节点 = 工作负载，边 = 「谁调谁」。
 *
 * <p>⚠️ {@code meshMetricsAvailable} 判的是「这个集群**在不在采集**网格指标」（哨兵 {@code istio_build}），
 * <b>不是</b>「有没有流量」。两者主查询都是空的，只有哨兵能分开 —— 前端因此有两种不同的空态文案。
 */
@Data
@Schema(description = "服务网格拓扑")
public class MeshTopologyResponse {

    @Schema(description = "节点（由边两端推出，不含「在网格里但没流量」的负载）")
    private List<MeshNode> nodes = new ArrayList<>();

    @Schema(description = "边：源 → 目标")
    private List<MeshEdge> edges = new ArrayList<>();

    @Schema(description = "查询时间窗（秒）")
    private int windowSeconds;

    @Schema(description = "true = 这个集群在采集网格指标（与有没有流量无关）")
    private boolean meshMetricsAvailable;

    /** 拓扑里的一个节点。 */
    @Data
    @Schema(description = "拓扑节点")
    public static class MeshNode {

        /** ⚠️ `命名空间/工作负载`。命名空间必须进 id —— 同名 workload 跨命名空间很常见。 */
        @Schema(description = "id = 命名空间/工作负载")
        private String id;

        @Schema(description = "命名空间")
        private String namespace;

        @Schema(description = "工作负载名")
        private String workload;

        /** 外部网关（`istio-system` 里那些）：它是"外部入口"，前端要画得不一样。 */
        @Schema(description = "是否外部网关")
        private boolean gateway;
    }

    /** 拓扑里的一条边。 */
    @Data
    @Schema(description = "拓扑边")
    public static class MeshEdge {

        @Schema(description = "源节点 id")
        private String source;

        @Schema(description = "目标节点 id")
        private String target;

        /**
         * `L7` = 有请求数/响应码/延迟（来自 Envoy 类代理：外部网关、waypoint）；
         * `L4` = 只有连接与字节（来自 ztunnel），**没有错误率、没有请求延迟**。
         * 同一对端点同时有两者时只出一条 `L7` 边，字节数一并带上。
         */
        @Schema(description = "L7 | L4")
        private String level;

        @Schema(description = "请求数/秒（仅 L7；L4 为 0，无意义）")
        private double rps;

        /** 5xx / 总量。分母是**总量**（错误查询是总量的子集），不是 rps+errors。仅 L7。 */
        @Schema(description = "错误率 0..1（仅 L7）")
        private double errorRate;

        /** ⚠️ 窗口内没有样本时是 **null**（Thanos 会给 NaN），不是 0 —— 0 会被读成"延迟为 0ms"。 */
        @Schema(description = "P95 延迟（毫秒，仅 L7），无样本为 null")
        private Double p95Ms;

        /** L4 的流量口径：字节/秒。仅 L4 边，或"同一对端点也有 L4 流量"的 L7 边。 */
        @Schema(description = "字节/秒（L4 流量；没有则为 null）")
        private Double bytesPerSec;
    }
}
