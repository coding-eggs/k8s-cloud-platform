package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "命名空间视图：K8s 命名空间 + 分配信息合并（api 侧业务加工）")
public class NamespaceView {

    @Schema(description = "命名空间名")
    private String name;

    @Schema(description = "状态（Active/Terminating）")
    private String phase;

    @Schema(description = "创建时间（ISO-8601）")
    private String creationTimestamp;

    @Schema(description = "是否平台创建（带 managed-by 标签，纯来源展示）")
    private boolean managedBy;

    @Schema(description = "是否可编辑/删除/设配额/限制范围（false = 命中受保护系统命名空间名单）")
    private boolean editable;

    @Schema(description = "已分配租户名；null = 未分配")
    private String allocatedTenantName;

    @Schema(description = "描述（metadata.annotations[\"description\"]）")
    private String description;

    @Schema(description = "标签（不含 managed-by 等平台保留键由前端按需展示）")
    private Map<String, String> labels;

    @Schema(description = "Calico 绑定 IPv4 地址池（ns annotation cni.projectcalico.org/ipv4pools；null=默认分配）")
    private List<String> ipv4Pools;

    @Schema(description = "Calico 绑定 IPv6 地址池（ns annotation cni.projectcalico.org/ipv6pools；null=默认分配）")
    private List<String> ipv6Pools;

    @Schema(description = "Istio ambient 数据面模式（ns label istio.io/dataplane-mode；ambient / none；null=未设，跟随集群默认）")
    private String dataplaneMode;

    @Schema(description = "Istio L7 waypoint（ns label istio.io/use-waypoint；= waypoint Gateway 名 或 none；null=未设）")
    private String useWaypoint;

}
