/**
 * 终端心跳的每 tick 决策（PodTerminal 用）。
 *
 * 每 {@link HEARTBEAT_CHECK_MS} 检查一次：静默超过 {@link SILENCE_LIMIT_MS} 判断线；
 * 距上次 ping 满 {@link PING_INTERVAL_MS} 再发 ping（api 中继直接回 pong，让 nginx / 网关 / LB 都看到流量）。
 * 检查间隔与 ping 间隔分开：断线最晚在 60s + 10s 内发现（检查挂在 25s 的 ping 上时最晚约 85s）。
 */
export const PING_INTERVAL_MS = 25_000
export const SILENCE_LIMIT_MS = 60_000
export const HEARTBEAT_CHECK_MS = 10_000

export type HeartbeatAction = 'dead' | 'ping' | 'none'

export function heartbeatAction(now: number, lastFrameAt: number, lastPingAt: number): HeartbeatAction {
  if (now - lastFrameAt > SILENCE_LIMIT_MS) return 'dead'
  if (now - lastPingAt >= PING_INTERVAL_MS) return 'ping'
  return 'none'
}
