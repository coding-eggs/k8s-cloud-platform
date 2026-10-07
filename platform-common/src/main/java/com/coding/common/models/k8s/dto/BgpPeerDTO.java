package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Calico BGPPeer DTO（projectcalico.org/v3，集群级 CRD，<b>只读</b>）。
 * <p>字段按 projectcalico/api v3.28→master 并集建模（view-only：revert 读到什么展示什么，缺席=null）。
 * 注意真实 schema：对端地址是 {@code spec.peerIP}（可带端口）、{@code nodeSelector} 是<b>字符串</b>（非列表）、
 * asNumber 为 numorstring → String、sourceAddress 为枚举 UseNodeIP/None。
 * 端点走 k8s-server {@code /admin/calico/bgppeer}，仅 list/get/yaml。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BgpPeerDTO extends BaseResources {

    /** spec.node（目标 Calico 节点名；与 nodeSelector 互斥） */
    private String node;

    /** spec.nodeSelector（节点选择器表达式，字符串） */
    private String nodeSelector;

    /** spec.peerIP（对端地址，可带端口：10.0.0.1:179 / [fd00::1]:179） */
    private String peerIp;

    /** spec.asNumber（对端 AS 号，numorstring） */
    private String asNumber;

    /** spec.localASNumber（本地 AS 号，master 新增；缺省用 default BGPConfiguration） */
    private String localAsNumber;

    /** spec.peerSelector（远端节点选择器；设置时 peerIP/asNumber 须为空） */
    private String peerSelector;

    /** spec.keepOriginalNextHop（保留原始 next-hop；已废弃，建议看 nextHopMode） */
    private Boolean keepOriginalNextHop;

    /** spec.nextHopMode（Auto/Self/Keep，master 新增，取代 keepOriginalNextHop） */
    private String nextHopMode;

    /** spec.password.secretKeyRef（BGP 密码 Secret 引用；只存引用不存密文） */
    private BgpSecretKeyRefDTO password;

    /** spec.sourceAddress（UseNodeIP/None：peering 源地址策略） */
    private String sourceAddress;

    /** spec.maxRestartTime（graceful restart 超时，K8s Duration 如 "120s"） */
    private String maxRestartTime;

    /** spec.keepaliveTime（BGP keepalive 间隔，master 新增） */
    private String keepaliveTime;

    /** spec.numAllowedLocalASNumbers（允许 AS path 中的本地 AS 数；去除环路防护，慎用） */
    private Integer numAllowedLocalASNumbers;

    /** spec.ttlSecurity（GTSM 跳数） */
    private Integer ttlSecurity;

    /** spec.reachableBy（防路由抖动的 /32 静态路由网关地址） */
    private String reachableBy;

    /** spec.filters（作用于该 peer 的 BGPFilter 有序列表） */
    private List<String> filters;

    /** spec.localWorkloadSelector（本地 workload peering 选择器，master 新增） */
    private String localWorkloadSelector;

    /** spec.reversePeering（Auto/Manual：反向 peering 是否自动生成，master 新增） */
    private String reversePeering;

    /** status.conditions（只读；v3 无 status 时 null） */
    private List<ConditionDTO> conditions;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/admin/calico/bgppeer";
    }

}
