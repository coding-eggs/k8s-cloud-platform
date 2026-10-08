package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Calico BGPConfiguration DTO（projectcalico.org/v3，集群级 CRD，<b>只读</b>）。
 * <p>字段按 projectcalico/api v3.28→master 并集建模（view-only：revert 读到什么展示什么，缺席=null）。
 * asNumber 为 numorstring（数字或 "AS64512" 串）→ 统一 String。端点走 k8s-server {@code /calico/bgpconfiguration}，仅 list/get/yaml。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BgpConfigurationDTO extends BaseResources {

    /** spec.logSeverityScreen（日志级别：Trace/Debug/Info/Warning/Error/Fatal） */
    private String logSeverityScreen;

    /** spec.nodeToNodeMeshEnabled（全节点 BGP mesh 开关） */
    private Boolean nodeToNodeMeshEnabled;

    /** spec.asNumber（默认 AS 号，numorstring：64512 或 "AS64512"） */
    private String asNumber;

    /** spec.listenPort（BGP 监听端口，默认 179） */
    private Integer listenPort;

    /** spec.serviceClusterIPs（Service ClusterIP 宣告块） */
    private List<BgpServiceBlockDTO> serviceClusterIPs;

    /** spec.serviceExternalIPs（Service ExternalIP 宣告块） */
    private List<BgpServiceBlockDTO> serviceExternalIPs;

    /** spec.serviceLoadBalancerIPs（Service LoadBalancer IP 宣告块） */
    private List<BgpServiceBlockDTO> serviceLoadBalancerIPs;

    /** spec.communities（community 名称↔值映射表） */
    private List<BgpCommunityDTO> communities;

    /** spec.prefixAdvertisements（按前缀的宣告配置） */
    private List<BgpPrefixAdvertisementDTO> prefixAdvertisements;

    /** spec.nodeMeshPassword.secretKeyRef（node-mesh BGP 密码，仅 default 配置可设；只存引用不存密文） */
    private BgpSecretKeyRefDTO nodeMeshPassword;

    /** spec.bindMode（None=全部地址 / NodeIP=仅节点规范 IP） */
    private String bindMode;

    /** spec.ignoredInterfaces（读取设备路由时排除的网卡） */
    private List<String> ignoredInterfaces;

    // ---- 新版（master，3.28 缺席→null）----

    /** spec.serviceLoadBalancerAggregation（Enabled/Disabled：LB IP 按段还是 /32 宣告） */
    private String serviceLoadBalancerAggregation;

    /** spec.localWorkloadPeeringIPV4（本地 workload peering 虚拟 v4 地址） */
    private String localWorkloadPeeringIPV4;

    /** spec.localWorkloadPeeringIPV6（本地 workload peering 虚拟 v6 地址） */
    private String localWorkloadPeeringIPV6;

    /** spec.programClusterRoutes（Enabled/Disabled：跨节点 workload 路由由 confd/BIRD 还是 Felix 编程） */
    private String programClusterRoutes;

    /** spec.ipv4NormalRoutePriority（IPv4 普通路由优先级，须与 FelixConfiguration 一致） */
    private Integer ipv4NormalRoutePriority;

    /** spec.ipv6NormalRoutePriority（IPv6 普通路由优先级，须与 FelixConfiguration 一致） */
    private Integer ipv6NormalRoutePriority;

    /** status.conditions（只读；v3 无 status 时 null） */
    private List<ConditionDTO> conditions;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/calico/bgpconfiguration";
    }

}
