package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工作负载 ambient 开关（B3 §11.3）：只改 pod template 的两个 istio 保留 label，不碰其余字段。
 *
 * <p>三态语义（两个字段各自独立，与命名空间侧的绑定池/ambient 字段同一口径）：
 * <ul>
 *   <li>{@code null} —— <b>不动</b>该 label（不是"清除"）。列表页的 ambient 开关与 L7 开关各发各的，
 *       不会互相踩；未探测到 Istio 时前端不传字段，也不会误清用户既有配置。</li>
 *   <li>{@code ""}（空串）—— <b>移除</b>该 label（回到"跟随命名空间 / 集群默认"）。</li>
 *   <li>有值 —— 覆写：{@code dataplaneMode} ∈ {ambient, none}；{@code useWaypoint} = waypoint Gateway 名 或 none。</li>
 * </ul>
 *
 * <p>{@code kind} 可不传：k8s-server 按 name 跨 Deployment/StatefulSet/DaemonSet 查找（同 get/delete 的既有约定）。
 */
@Data
@Schema(description = "工作负载 ambient 开关请求（dataplaneMode / useWaypoint 各自独立三态：null=不动、空串=移除、有值=覆写）")
public class WorkloadMeshToggleRequest {

    @Schema(description = "工作负载名（路径变量回填；跨 kind 查找用）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "租户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "工作负载类型（deployment/statefulset/daemonset）；不传则按 name 跨 kind 查找")
    private String kind;

    @Schema(description = "istio.io/dataplane-mode：ambient / none；空串=移除该 label（跟随集群默认）；不传=不动")
    private String dataplaneMode;

    @Schema(description = "istio.io/use-waypoint：waypoint Gateway 名 或 none；空串=移除该 label；不传=不动")
    private String useWaypoint;
}
