package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * Service DTO（基础表单字段）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ServiceDTO extends BaseResources {

    /** ClusterIP / NodePort / LoadBalancer / ExternalName，缺省 ClusterIP */
    private String type = "ClusterIP";

    private List<ServicePortDTO> ports;

    /** Pod 选择器（label map）：绑定工作负载/Pod 时由后端解析填充；留空=端点由外部管理。仅非 ExternalName */
    private Map<String, String> selector;

    /** 集群 IP：创建可指定（空=自动分配 / "None"=headless / 具体 IP）；编辑时 K8s 禁改。仅查询亦返回 */
    private String clusterIp;

    // ---- 网络 / IP（除 ExternalName 外适用）----
    /** 集群 IP 列表（双栈最多 2 个，须与 ipFamilies 对应；[0] 必须等于 clusterIp）。仅查询/创建 */
    private List<String> clusterIps;
    /** 节点额外接受的 IP（K8s 不管理） */
    private List<String> externalIps;
    /** IP 族列表（IPv4 / IPv6，双栈最多 2 个） */
    private List<String> ipFamilies;
    /** 单/双栈策略：SingleStack / PreferDualStack / RequireDualStack，缺省 SingleStack */
    private String ipFamilyPolicy;

    // ---- 流量策略 ----
    /** 内部（ClusterIP）流量策略：Cluster / Local */
    private String internalTrafficPolicy;
    /** 外部（NodePort/ExternalIPs/LB）流量策略：Cluster / Local */
    private String externalTrafficPolicy;
    /** 会话保持：None / ClientIP，缺省 None */
    private String sessionAffinity;
    /** ClientIP 会话保持秒数（1–86400，默认 10800）；仅 sessionAffinity=ClientIP 生效。对应 spec.sessionAffinityConfig.clientIP.timeoutSeconds */
    private Integer sessionAffinityTimeoutSeconds;
    /** 忽略 ready/not-ready，未就绪 Pod 也纳入端点（StatefulSet headless 对等发现常用） */
    private Boolean publishNotReadyAddresses;

    // ---- LoadBalancer 专属 ----
    /** LB 是否自动分配 NodePort，缺省 true；仅 type=LoadBalancer */
    private Boolean allocateLoadBalancerNodePorts;
    /** LB 健康检查 nodePort；仅 type=LoadBalancer 且 externalTrafficPolicy=Local；一旦设置不可改 */
    private Integer healthCheckNodePort;
    /** LB 实现类别（label 风格标识）；仅 LoadBalancer；一旦设置不可改 */
    private String loadBalancerClass;
    /** 限制经云 LB 的源 IP / CIDR */
    private List<String> loadBalancerSourceRanges;

    // ---- ExternalName 专属 ----
    /** 外部别名（CNAME，小写 RFC-1123）；仅 type=ExternalName */
    private String externalName;

    /** selector 绑定来源（平台侧）：type=workload|pod + name。后端据此解析 selector；非 K8s 字段，转发 k8s-server 前剥离、不落库 */
    private SelectorRef selectorRef;

    @Data
    public static class SelectorRef {
        /** workload / pod */
        private String type;
        /** 工作负载名 / Pod 名 */
        private String name;
    }

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/services";
    }

}
