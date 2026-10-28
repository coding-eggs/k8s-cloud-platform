package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 对外入口 Gateway 的一个 listener（由安装表单的「端口列表」派生）。
 *
 * <p>协议是 Gateway API 的 listener 协议（HTTP / HTTPS / TLS / TCP / UDP），
 * 与 Service 的 protocol（TCP/UDP）不是一回事：Service 的协议推不出 L7 协议，
 * 所以端口表里要单独选。HTTPS 走 Terminate，必须有证书 Secret；TLS 走 Passthrough，不挂证书。
 */
@Data
public class GatewayListenerDTO {
    private String name;
    private Integer port;
    /** HTTP / HTTPS / TLS / TCP / UDP */
    private String protocol;
    /** HTTPS/TLS 的 TLS 模式：Terminate（要证书）/ Passthrough（不挂证书）；空 = 按协议取默认 */
    private String tlsMode;
    /** Terminate 用的证书 Secret 名（同 Gateway 命名空间）；Passthrough 不用 */
    private String tlsSecret;
    /**
     * listener 的 hostname（可空 = 该端口上接受任意 Host）。
     *
     * <p>同一端口放多条 HTTPS listener 时**必须**各自写 hostname：Gateway API 要求
     * (port, hostname) 唯一，Envoy 也靠 SNI 选 listener 与证书。Harbor 与 S3 都要占 443，
     * 就靠它区分。
     */
    private String hostname;
}
