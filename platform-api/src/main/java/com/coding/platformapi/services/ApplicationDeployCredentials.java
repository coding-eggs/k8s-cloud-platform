package com.coding.platformapi.services;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 部署操作的调用凭据（内存，不落库）。
 *
 * <p>为什么需要它：部署是异步阶段机，worker 线程没有请求上下文，而 k8s-server 对匿名调用一律拒绝。
 * 发起部署时把当次请求的 Authorization 记下来，worker 用 {@code K8sServerGateway.runAs} 带过去。
 *
 * <p><b>为什么不落库</b>：access token 是用户凭据，写进数据库等于把可复用的身份留在磁盘上。
 * 代价是 platform-api 重启后这些凭据就没了 —— 重启时还在跑的操作会以「凭据已失效，请重新部署」
 * 失败，而不是假装能续跑。
 */
@Component
public class ApplicationDeployCredentials {

    private final Map<Long, String> byOperation = new ConcurrentHashMap<>();

    public void remember(Long operationId, String authorization) {
        if (operationId != null && authorization != null && !authorization.isBlank()) {
            byOperation.put(operationId, authorization);
        }
    }

    public String get(Long operationId) {
        return operationId == null ? null : byOperation.get(operationId);
    }

    public void forget(Long operationId) {
        if (operationId != null) {
            byOperation.remove(operationId);
        }
    }
}
