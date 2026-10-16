package com.coding.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;

/**
 * 显式启用基于 Redis 的 HTTP 会话（作为 session-renewal grant 的根凭证存储）。
 *
 * <p><b>为什么需要这个类：</b>Spring Boot 4.x 已移除 {@code spring.session.*} 的自动装配
 * （3.x 里仅把 starter 放上 classpath 即自动生效，4.0.6 的 autoconfigure 中已无
 * {@code SessionAutoConfiguration}/{@code RedisSessionConfiguration}）。因此必须用本注解显式启用。
 *
 * <p>⚠️ <b>是 {@code Indexed} 那个，不是 {@code @EnableRedisHttpSession}</b>（2026-10-16 修正）。
 * Spring Session 3.0 起这两个注解分了家：
 * <ul>
 *   <li>{@code @EnableRedisHttpSession} → {@code RedisSessionRepository}，**没有**索引。
 *       本类原来用的就是它，而 javadoc 里却写着"创建 RedisIndexedSessionRepository" ——
 *       文档是照着更早的版本写的，代码早就不是那个意思了。</li>
 *   <li>{@code @EnableRedisIndexedHttpSession} → {@code RedisIndexedSessionRepository}，
 *       多一份「**按用户名**（principal）索引会话」的表。</li>
 * </ul>
 * 这个差别**不报错、只是静默地少一样东西**：线上 Redis 里 21 个
 * {@code spring:session:sessions:*}、**0 个** {@code spring:session:index:*}，
 * 于是"改密码把别的设备踢下去"一个都踢不到。自助改密码（S1.1）就靠这张索引。
 *
 * <p>代价：多维护三类键 —— {@code index:*}、{@code expirations:*} 与
 * {@code sessions:expires:<id>} 影子键（Spring 当初把它拆出去就是为了省这点内存），
 * 对本平台的量级无所谓。
 *
 * <p>⭐ <b>怎么一眼判断"新构建到底有没有在跑"（不用登录）</b>：看有没有**影子键**。
 * <pre>redis-cli -h &lt;host&gt; -n 1 --scan --pattern 'spring:session:sessions:expires:*'</pre>
 * 索引版**每一次 save 都建**它（{@code saveDelta()} 里的 {@code createShadowKey}），旧版一个都没有。
 * ⚠️ 别拿 {@code expirations:*} 当判据 —— 那一个**不能用**：它只在"会话的到期分钟变了"时才动，
 * 而且**本机起个调试实例连同一个 Redis 就会写**（2026-10-16 就是这么被自己误导的：
 * 把 {@code expirations:*} 当成了"线上新构建已生效"，实际是调试实例留下的）。
 * 影子键才是"每一次保存都会留"的那种指纹。
 *
 * <p>⚠️ <b>切换不影响已有会话</b>：两类仓库用同一个 {@code RedisSessionMapper} 写同一批
 * {@code spring:session:sessions:*} 哈希，老会话照常能读出来。但它们**不在索引里、踢不到**，
 * 所以别以为换完注解、重启一下，"改密码踢别的设备"立刻就对所有在线用户生效 ——
 * 老会话要**重新登录一次**（登录那次 save 才会写 security context → 才会补索引），
 * 否则得等它自然过期（最长 {@link #SESSION_MAX_INACTIVE_SECONDS}）。
 *
 * <p>⚠️ 它启动时会对 Redis 执行 {@code CONFIG SET notify-keyspace-events}（过期会话的清理靠
 * 键空间通知）。**Redis 账号没有 {@code CONFIG} 权限的话，应用启动会直接失败** ——
 * 换集群 / 换 Redis 账号时要先确认这一条。
 *
 * <p>它还注册 {@code SpringSessionRepositoryFilter}，把 {@link jakarta.servlet.http.HttpSession}
 * 透明替换为 Redis 支撑的实现 —— 应用代码（converter / keepalive）仍走标准 HttpSession API。
 *
 * <p>会话空闲超时设为 8h（{@code maxInactiveIntervalInSeconds = 28800}），由 SPA 活跃期间定期调用
 * {@code /session/keepalive} 滑动续期。
 *
 * <p><b>连接保活：</b>Lettuce 同步命令走一条常驻共享连接，长时间空闲会被 Redis/中间设备 RST 关闭，导致
 * 下次续期首包 {@code Connection reset}（重试才成功）。由 {@link RedisConnectionKeepalive} 每 30s PING 一次
 * 保持该连接热并主动探活恢复，详见该类说明。
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
