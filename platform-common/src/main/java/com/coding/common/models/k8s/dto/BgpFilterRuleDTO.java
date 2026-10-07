package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * BGPFilter 规则单项（projectcalico.org/v3 {@code spec.{exportV4,importV4,exportV6,importV6}[]}）。
 * <p>v4/v6 规则结构相同（仅 CIDR 校验不同），共用本 DTO。展示向扁平化：
 * prefixLength{min,max} → prefixLengthMin/Max；communities{values[]} → communityValues；
 * operations 判别联合 → 三个互斥字段。真实 JSON key {@code interface} 是 Java 保留字 → 字段名 {@code iface}。
 */
@Data
public class BgpFilterRuleDTO {

    /** cidr（匹配前缀，常规 CIDR 记法） */
    private String cidr;

    /** prefixLength.min（前缀长度下限；须与 cidr 同设） */
    private Integer prefixLengthMin;

    /** prefixLength.max（前缀长度上限） */
    private Integer prefixLengthMax;

    /** source（RemotePeers：仅作用于从 BGP peer 学到的路由） */
    private String source;

    /** iface（出接口匹配；真实 JSON key = "interface"，Java 保留字故改名） */
    private String iface;

    /** matchOperator（Equal/NotEqual/In/NotIn；cidr 设置时必填） */
    private String matchOperator;

    /** peerType（eBGP/iBGP：仅作用于指定类型的 peer） */
    private String peerType;

    /** communities.values（community 匹配值，当前恰好一个） */
    private List<String> communityValues;

    /** asPathPrefix（AS path 前缀序列，numorstring 列表） */
    private List<String> asPathPrefix;

    /** priority（路由优先级匹配，1..2147483646） */
    private Integer priority;

    /** action（Accept/Reject，必填） */
    private String action;

    /** operations（仅 Accept 可用；有序，每项恰设一个操作字段） */
    private List<BgpFilterOperationDTO> operations;

}
