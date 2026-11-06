package com.coding.platformapi.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布执行的调用凭据：本进程内存 + Redis（多实例共享），不落库。
 *
 * <p>为什么需要它：发布是异步阶段机，worker 线程没有请求上下文，而 k8s-server 对匿名调用一律拒绝。
 * 发起发布 / 回滚 / 继续时把当次请求的 Authorization 记下来，worker 用 {@code K8sServerGateway.runAs} 带过去。
 *
 * <p><b>为什么要进 Redis</b>：持有执行的 api 实例被换掉（滚动更新，典型是平台「发布自己」）后，
 * 接手的实例必须拿得到凭据才能续跑；只在内存里的话接手方只能判「已中断」，让用户再点「继续」。
 *
 * <p><b>为什么不落库</b>：access token 是用户凭据，写进数据库等于把可复用的身份长期留在磁盘上。
 * Redis 里的副本 TTL = token 自身剩余有效期，过期后本来也用不了，不会比 token 活得更久。
 *
 * <p>Redis 只是优化：写 / 读失败都退化成仅内存（打 warn），行为回到「接手方判中断、用户点继续」。
 */
@Slf4j
@Component
public class ApplicationDeployCredentials {

    static final String KEY_PREFIX = "platform:app-release:cred:";

    /** 解析不出 exp 时的兜底 TTL */
    static final Duration FALLBACK_TTL = Duration.ofHours(1);

    private static final Pattern EXP = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");

    private final Map<Long, String> byExecution = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;

    /** 仅内存（测试 / 无 Redis） */
    public ApplicationDeployCredentials() {
        this.redis = null;
    }

    @Autowired
    public ApplicationDeployCredentials(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis.getIfAvailable();
    }

    /** 测试用 */
    ApplicationDeployCredentials(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void rememberExecution(Long executionId, String authorization) {
        if (executionId == null || authorization == null || authorization.isBlank()) {
            return;
        }
        byExecution.put(executionId, authorization);
        if (redis == null) {
            return;
        }
        Duration ttl = ttlOf(authorization, System.currentTimeMillis());
        if (ttl.isZero() || ttl.isNegative()) {
            return;   // 已过期：放进去也用不了
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + executionId, authorization, ttl);
        } catch (RuntimeException e) {
            log.warn("发布凭据写 Redis 失败（执行 {}），仅本实例可续跑：{}", executionId, e.getMessage());
        }
    }

    public String getExecution(Long executionId) {
        if (executionId == null) {
            return null;
        }
        String local = byExecution.get(executionId);
        if (local != null || redis == null) {
            return local;
        }
        try {
            String shared = redis.opsForValue().get(KEY_PREFIX + executionId);
            if (shared != null) {
                byExecution.put(executionId, shared);
            }
            return shared;
        } catch (RuntimeException e) {
            log.warn("发布凭据读 Redis 失败（执行 {}）：{}", executionId, e.getMessage());
            return null;
        }
    }

    public void forgetExecution(Long executionId) {
        if (executionId == null) {
            return;
        }
        byExecution.remove(executionId);
        if (redis == null) {
            return;
        }
        try {
            redis.delete(KEY_PREFIX + executionId);
        } catch (RuntimeException e) {
            log.warn("发布凭据删 Redis 失败（执行 {}），等 TTL 到期：{}", executionId, e.getMessage());
        }
    }

    /** TTL = JWT exp − now；不是 JWT / 没有 exp 时用兜底值 */
    static Duration ttlOf(String authorization, long nowMillis) {
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : authorization.trim();
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return FALLBACK_TTL;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Matcher m = EXP.matcher(payload);
            if (!m.find()) {
                return FALLBACK_TTL;
            }
            return Duration.ofMillis(Long.parseLong(m.group(1)) * 1000 - nowMillis);
        } catch (IllegalArgumentException e) {
            return FALLBACK_TTL;
        }
    }
}
