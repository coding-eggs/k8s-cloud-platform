package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 节点地址窄投影（列表页 NodePort 直连用）：只回节点名 + 两个 IP + Ready。
 *
 * <p><b>为什么是窄投影而不是直接回 {@link NodeDTO}</b>：它的消费者是租户可达的工作负载列表页 ——
 * 点击 nodePort 要拼 {@code http://<节点IP>:<nodePort>}，只需要"哪台机器能连"。节点的
 * 容量/可分配量/标签/污点/Pod 数都是集群拓扑，跟这件事无关，不该因为一个跳转链接被顺带下发
 * （约定见 docs/development/frontend-permission-conventions.md §5）。
 *
 * <p>非 {@code BaseResources} 子类：没有 apiPath，不是六端点资源 CRUD 的载体。
 */
@Data
@Schema(description = "节点地址窄投影（NodePort 直连用）")
public class NodeAddressDTO {

    @Schema(description = "节点名")
    private String name;

    @Schema(description = "内网 IP（status.addresses[type=InternalIP]）")
    private String internalIp;

    @Schema(description = "外网 IP（status.addresses[type=ExternalIP]，未设置则为空）")
    private String externalIp;

    /** Ready 条件为 True。非 Ready 的节点仍返回（用户可能想自己试），但前端默认不选它 */
    @Schema(description = "节点是否 Ready")
    private Boolean ready;
}
