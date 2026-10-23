package com.coding.common.models.middleware;

import lombok.Data;

import java.util.List;

/**
 * 实例的连接方式（创建成功后弹给用户）。
 *
 * <p>只给**集群内**地址：平台不替用户做集群外暴露（Ingress/NodePort 属于租户自己的网络决定）。
 * 凭据给的是「去哪个 Secret 的哪个键取」，不把口令回传给前端。
 */
@Data
public class MiddlewareConnectionDTO {

    public record Line(String label, String value) {
    }

    private String instanceName;

    private String namespace;

    /** 集群内访问地址（Service DNS + 端口） */
    private List<Line> endpoints;

    /** 凭据位置（用户名 / 密码在哪个 Secret 的哪个键） */
    private List<Line> credentials;

    /** 附加说明（无认证、需要集群内访问等） */
    private String note;
}
