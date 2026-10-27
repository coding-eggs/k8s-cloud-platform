package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
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
@AllArgsConstructor
public class HarborInstallRequest {

    /** route / loadBalancer / nodePort（默认 nodePort） */
    private String exposeMode;

    /** 访问地址：route 用域名；另两种用节点/LB 的 IP 或域名（不带端口） */
    private String host;

    /** nodePort 模式的 NodePort（默认 30002） */
    private Integer nodePort;

    /** loadBalancer 模式的 Service 端口（默认 80） */
    private Integer port;

    /** 老调用与默认值：nodePort 模式 + 30002 + 80 */
    public HarborInstallRequest(String host) {
        this("nodePort", host, 30002, 80);
    }
}
