package com.coding.platformapi.services;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发布执行的调用凭据（内存，不落库）。
 *
 * <p>为什么需要它：发布是异步阶段机，worker 线程没有请求上下文，而 k8s-server 对匿名调用一律拒绝。
 * 发起发布 / 回滚 / 继续时把当次请求的 Authorization 记下来，worker 用 {@code K8sServerGateway.runAs} 带过去。
 *
 * <p><b>为什么不落库</b>：access token 是用户凭据，写进数据库等于把可复用的身份留在磁盘上。
 * 代价是 platform-api 重启后这些凭据就没了 —— 重启时还在跑的执行会进入「已中断」，由用户点「继续」
 * 重新带上自己的凭据，而不是假装能续跑。
 */
@Component
public class ApplicationDeployCredentials {

    private final Map<Long, String> byExecution = new ConcurrentHashMap<>();

    public void rememberExecution(Long executionId, String authorization) {
        if (executionId != null && authorization != null && !authorization.isBlank()) {
            byExecution.put(executionId, authorization);
        }
    }

    public String getExecution(Long executionId) {
        return executionId == null ? null : byExecution.get(executionId);
    }

    public void forgetExecution(Long executionId) {
        if (executionId != null) {
            byExecution.remove(executionId);
        }
    }
}
