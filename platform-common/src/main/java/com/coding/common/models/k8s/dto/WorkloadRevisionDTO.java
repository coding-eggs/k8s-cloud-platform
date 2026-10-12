package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 工作负载的一个历史版本（回滚选择用）。
 *
 * <p><b>历史存在哪里按 kind 分两条</b>（K8s 自身的机制，不是本平台的取舍）：
 * <ul>
 *   <li>Deployment —— 控制器每换一次 pod 模板就建一个 ReplicaSet，版本号写在它的
 *       {@code deployment.kubernetes.io/revision} 注解上（{@code kubectl rollout history} 读的也是它）</li>
 *   <li>StatefulSet / DaemonSet —— 版本存在 ControllerRevision 上（{@code .revision} 字段 + {@code .data} 里的完整对象）</li>
 * </ul>
 * 两条路最终都归一到本 DTO，前端只按 {@code revision} 选。
 *
 * <p>非 {@code BaseResources} 子类：不是六端点资源 CRUD 的载体。
 */
@Data
@Schema(description = "工作负载历史版本（回滚候选）")
public class WorkloadRevisionDTO {

    @Schema(description = "版本号（ReplicaSet 的 revision 注解 / ControllerRevision.revision）")
    private Long revision;

    @Schema(description = "承载该版本的对象名（ReplicaSet / ControllerRevision 名，排障用）")
    private String name;

    @Schema(description = "该版本创建时间（ISO-8601）")
    private String createdAt;

    /** {@code kubernetes.io/change-cause} 注解。平台不写这个注解，故通常为空——只展示集群里已有的 */
    @Schema(description = "变更说明（kubernetes.io/change-cause，平台不生成）")
    private String changeCause;

    /** 该版本各容器的镜像，顺序与容器一致（只取 containers，与列表页「镜像」列同口径） */
    @Schema(description = "容器镜像（按容器顺序）")
    private List<String> images;

    /** 是否当前生效的版本：Deployment 取 Deployment 自身的 revision 注解；STS/DS 取 pod 模板与线上相等的那条 */
    @Schema(description = "是否当前版本")
    private Boolean current;
}
