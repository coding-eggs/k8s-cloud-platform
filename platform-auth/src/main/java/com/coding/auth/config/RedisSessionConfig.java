package com.coding.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;

/**
 * 显式启用基于 Redis 的 HTTP 会话 —— session-renewal grant 的**根凭证**。
 * Boot 4.x 已移除 {@code spring.session.*} 的自动装配，所以必须显式注解。
 *
 * <p>⚠️ <b>必须是 {@code Indexed} 这个。</b>Spring Session 3.0 起两个注解分了家：
 * {@code @EnableRedisHttpSession} 建的是**无索引**的 {@code RedisSessionRepository}；
 * 要"按用户名找会话"（自助改密码踢别的设备靠它）就得用本注解。
 * ⚠️ 选错**不报错**：会话一切正常，只是 {@code index:*} 一个都没有，
 * **静默地一个都踢不到**。
 *
 * <p>怎么确认它真的在跑（不用登录）—— 看**影子键**，索引版每次 save 都建、旧版一个没有：
 * <pre>redis-cli -n 1 --scan --pattern 'spring:session:sessions:expires:*'</pre>
 * ⚠️ 别拿 {@code expirations:*} 判 —— 本机起个调试实例连同一个 Redis 也会写它。
 * （代价：多维护 index / expirations 两类键，本平台量级无所谓。）
 *
 * <p>⚠️ 索引只在"**这次改动里含 security context**"的那一次 save 才写，
 * 所以**换注解之前建的会话踢不到** —— 要重新登录一次才会补上，
 * 否则等它自然过期（最长 {@link #SESSION_MAX_INACTIVE_SECONDS}）。
 *
 * <p>⚠️ 启动会对 Redis 执行 {@code CONFIG SET notify-keyspace-events}（过期清理靠键空间通知）。
 * **Redis 账号没有 {@code CONFIG} 权限的话，应用直接起不来** —— 换集群/换账号先确认这条。
 *
 * <p>另外：本注解还负责把 HttpSession 换成 Redis 实现；空闲超时 8h，靠 SPA 的
 * {@code /session/keepalive} 滑动续期（Lettuce 连接保活见 {@link RedisConnectionKeepalive}）。
 */
@Configuration
@EnableRedisIndexedHttpSession(maxInactiveIntervalInSeconds = RedisSessionConfig.SESSION_MAX_INACTIVE_SECONDS)
public class RedisSessionConfig {

    /**
     * 会话空闲超时（秒）= 8h，即「根凭证」（HttpOnly 会话 Cookie）的寿命上限，由 SPA 活跃期间定期调用
     * {@code /session/keepalive} 滑动续期。
     *
     * <p>平台不变式：<b>Access Token TTL 必须严格小于本值</b>——否则 token 比 session 活得久，会出现
     * 「session 已失效但 token 仍有效」的孤儿窗口，用户活跃时反而被踢、被迫重登。该约束在客户端注册时由
     * {@code ClientService#validate} 强制校验。本常量是会话超时的<b>唯一来源</b>：注解与注册校验都引用它，
     * 避免两处数值漂移。
     */
    public static final int SESSION_MAX_INACTIVE_SECONDS = 28800;
}
