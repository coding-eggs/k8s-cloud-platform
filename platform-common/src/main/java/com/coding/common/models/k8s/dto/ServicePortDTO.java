package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * Service 端口（基础表单字段）
 */
@Data
public class ServicePortDTO {

    /** 端口名（多端口时必填，单端口可空） */
    private String name;

    /** Service 端口（1-65535） */
    private Integer port;

    /** 目标端口：数字或容器端口名 */
    private String targetPort;

    /** NodePort 类型时的节点端口（可空，由集群分配） */
    private Integer nodePort;

    /** 协议，缺省 TCP */
    private String protocol = "TCP";

    /** 应用层协议（如 http/grpc）：spec.ports 为 atomic list，须建模透传否则外部设的值在保存时被抹掉；UI 不编辑 */
    private String appProtocol;

}
