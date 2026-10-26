package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Harbor 安装参数（仅 harborRegistry 的组件）。
 *
 * <p>{@code host} 是 Harbor 的访问域名（host[:port]，不带 scheme/path）。它同时决定
 * HTTPRoute 的 hostnames 与 {@code externalURL} —— 后者是 Harbor token 服务返回给
 * docker 客户端的地址，写错就会「登录成功但 push 失败」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HarborInstallRequest {
    private String host;
}
