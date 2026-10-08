package com.coding.k8sserver.controllers;


import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 仅剩 {@code /callback}：OAuth 授权码回跳占位（{@code ResourceServerConfig} 对 {@code /callback} 显式 permitAll）。
 *
 * <p><b>本批删除的调试残留</b>：
 * <ul>
 *   <li>{@code GET /test} —— 把整个 JWT 打到 stdout 的联调端点，且不在豁免清单里（只是碰巧没人发现）。</li>
 *   <li>授权码的 {@code System.out.println} —— permitAll 端点把 code 写进服务端日志。</li>
 *   <li>一个只打印 BigInteger 比较结果的 {@code main} 方法。</li>
 * </ul>
 * <b>不要在本类恢复联调端点</b>：任何新增 handler 都必须实现
 * {@code com.coding.k8sserver.components.AccessBoundaryAware}，否则会被
 * {@code BoundaryAuthorizationManager} 拒绝（fail-closed，含平台管理员一起 403）。
 */
@RestController
public class TestController {

    @GetMapping("/callback")
    public void callback(@RequestParam(value = "code", required = false) String code, HttpServletResponse response) throws IOException {

        String target = (code != null)
                ? "https://www.baidu.com?code=" + code
                : "https://www.baidu.com?error=no_code";

        response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);  // 302
        response.setHeader("Location", target);
    }

}
