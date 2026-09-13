package com.coding.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * 显式启用基于 Redis 的 HTTP 会话（作为 session-renewal grant 的根凭证存储）。
 *
 * <p><b>为什么需要这个类：</b>Spring Boot 4.x 已移除 {@code spring.session.*} 的自动装配
 * （3.x 里仅把 starter 放上 classpath 即自动生效，4.0.6 的 autoconfigure 中已无
 * {@code SessionAutoConfiguration}/{@code RedisSessionConfiguration}）。因此必须用本注解显式启用。
 *
 * <p>{@link EnableRedisHttpSession} 会：
 * <ul>
 *   <li>创建 {@code RedisIndexedSessionRepository}（复用 Boot 自动装配的 LettuceConnectionFactory，
 *       连接参数来自 {@code spring.data.redis.*}）；</li>
 *   <li>注册 {@code SpringSessionRepositoryFilter}，把 {@link jakarta.servlet.http.HttpSession}
 *       透明替换为 Redis 支撑的实现 —— 应用代码（converter / keepalive）仍走标准 HttpSession API。</li>
 * </ul>
 *
 * <p>会话空闲超时设为 8h（{@code maxInactiveIntervalInSeconds = 28800}），由 SPA 活跃期间定期调用
 * {@code /session/keepalive} 滑动续期。
 *
 * <p><b>连接保活：</b>Lettuce 同步命令走一条常驻共享连接，长时间空闲会被 Redis/中间设备 RST 关闭，导致
 * 下次续期首包 {@code Connection reset}（重试才成功）。由 {@link RedisConnectionKeepalive} 每 30s PING 一次
 * 保持该连接热并主动探活恢复，详见该类说明。
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = RedisSessionConfig.SESSION_MAX_INACTIVE_SECONDS)
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
