/**
 * 终端输入分块：一次粘贴是 xterm 的一个 onData，整段塞进一帧 {"type":"input"} 会超过 platform-api（Tomcat）
 * WebSocket 文本消息默认 8192 字符的缓冲 → 1009 断开（k8s-server 那一跳同理）。前端切小块逐帧发，三跳都不用改。
 *
 * 块大小 1024 字符：JSON 转义最坏 6 倍（控制字符 → \u00XX），1024 × 6 + 帧外壳仍 < 8192。
 * 同一条 WS 上按序发送，服务端按序写进 stdin，拼接结果与原文一致。
 */
export const TERMINAL_INPUT_CHUNK = 1024

export function chunkTerminalInput(data: string): string[] {
  const parts: string[] = []
  let i = 0
  while (i < data.length) {
    let end = Math.min(i + TERMINAL_INPUT_CHUNK, data.length)
    // 不劈开 UTF-16 代理对（emoji 等）：块尾若是高位代理，留到下一块
    const last = data.charCodeAt(end - 1)
    if (end < data.length && last >= 0xd800 && last <= 0xdbff) end--
    parts.push(data.slice(i, end))
    i = end
  }
  return parts
}
