package com.coding.platformapi.models;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Harbor 安装参数（仅 harborRegistry 的组件）。
 *
 * <p>暴露方式三选一：{@code route}（HTTPRoute，**必须域名**）、{@code loadBalancer}
 * 与 {@code nodePort}（可直接用 IP）。它同时决定 Service/HTTPRoute 与 {@code externalURL} ——
 * 后者是 Harbor token 服务返回给 docker 客户端的地址，写错就会「登录成功但 push 失败」。
 */
@Data
@NoArgsConstructor
public class HarborInstallRequest {

    /** route / loadBalancer / nodePort（默认 nodePort） */
    private String exposeMode;

    /** 访问地址：route 用域名；另两种用节点/LB 的 IP 或域名（不带端口） */
    private String host;

    /** nodePort 模式的 NodePort（默认 30002） */
    private Integer nodePort;

    /** loadBalancer 模式的 Service 端口（默认 80） */
    private Integer port;

    /** 数据库来源：internal / external（**只能在首次安装时选**） */
    private String databaseMode;

    private String dbHost;

    private Integer dbPort;

    private String dbUsername;

    /** 库名，默认 registry（外部库需预先建好） */
    private String dbName;

    /** disable / require / verify-ca / verify-full */
    private String dbSslMode;

    /**
     * 外部数据库口令：**只作为入参**，由 k8s-server 写进 Secret，不进 values 快照、不回显。
     * 编辑时留空 = 沿用已有 Secret。
     */
    private String dbPassword;

    /** Redis 来源：internal / external（只能在首次安装时选） */
    private String redisMode;

    /** 外部 Redis 地址 host:port（sentinel 列表暂不支持） */
    private String redisAddr;

    /** ACL 用户名（可空 = default 用户） */
    private String redisUsername;

    /** 外部 Redis 口令：同 dbPassword，只作入参 */
    private String redisPassword;

    /** 老调用与默认值：nodePort 模式 + 30002 + 80 + 内置数据库/Redis */
    public HarborInstallRequest(String host) {
        this("nodePort", host, 30002, 80);
    }

    public HarborInstallRequest(String exposeMode, String host, Integer nodePort, Integer port) {
        this.exposeMode = exposeMode;
        this.host = host;
        this.nodePort = nodePort;
        this.port = port;
        this.databaseMode = "internal";
        this.dbPort = 5432;
        this.dbName = "registry";
        this.dbSslMode = "disable";
        this.redisMode = "internal";
    }
}
