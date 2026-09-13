package com.coding.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性 PING Redis，保持共享 Lettuce 连接「热」并主动探活。
 *
 * <p><b>解决的问题：</b>session-renewal grant 依赖 Redis 里的 HttpSession。Lettuce 对同步命令使用一条
 * <b>常驻共享连接</b>（{@code shareNativeConnection} 默认开启）。当应用长时间没有会话读写时，Redis 服务端
 * 或中间网络设备（防火墙 / LB）会因空闲而 RST 关闭这条 TCP 连接。下一次续期在这条<b>已被对端关闭的僵尸连接</b>
 * 上发首个命令（{@code hGetAll} 读 session）即抛 {@code SocketException: Connection reset}，Lettuce 随后自动
 * 重连——表现为「偶发跳登录、重试一次就好」。
 *
 * <p><b>为什么用定时 PING：</b>
 * <ul>
 *   <li>持续有流量 → 中间设备的空闲超时不会触发，连接基本不会被静默断开；</li>
 *   <li>即便某次被断开，PING 会在 {@code fixedDelay} 内探测到死连接并触发 Lettuce 自动重连，
 *       使<b>用户续期到达时连接已恢复为健康状态</b>，首个命令不再失败。</li>
 * </ul>
 *
 * <p>PING 走的是与会话读写<b>同一条共享连接</b>（{@code factory.getConnection()} 返回其包装），因此探活的就是
 * 真正承载 session 命令的那条 socket。异常被吞掉并降级为 debug 日志——单次 PING 失败由 Lettuce 自动重连兜底，
 * 不应影响业务线程。
 */
@Component
public class RedisConnectionKeepalive {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionKeepalive.class);

    private final RedisConnectionFactory connectionFactory;

    public RedisConnectionKeepalive(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * 每 30s PING 一次。间隔需明显小于中间设备的空闲超时（常见 ≥60s~数百秒），故 30s 足够；
     * 单次开销仅一条 PING，可忽略。首次延迟 10s，等上下文与连接就绪后再开始探活。
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 10_000L)
    public void ping() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
        } catch (Exception e) {
            // 连接此刻不可用：Lettuce autoReconnect 会在后台恢复，下个周期 PING 即成功。仅记录不抛出。
            log.debug("Redis keepalive ping failed (will self-heal via auto-reconnect): {}", e.getMessage());
        }
    }
}
