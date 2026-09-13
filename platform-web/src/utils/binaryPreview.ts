/** ConfigMap binaryData 预览：base64 → 图片 / 文本 / 二进制下载（编辑器与详情抽屉共用） */

export type BinPreview =
  | { kind: 'image'; dataUrl: string; size: number }
  | { kind: 'text'; text: string; size: number }
  | { kind: 'binary'; dataUrl: string; size: number }

function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes
}

/** 按魔数识别图片（回显时无 mime，靠字节判断） */
function sniffImage(b: Uint8Array): string | null {
  if (b.length < 4) return null
  if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return 'image/png'
  if (b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg'
  if (b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x38) return 'image/gif'
  if (b.length >= 12 && b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46 &&
      b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50) return 'image/webp'
  if (b[0] === 0x00 && b[1] === 0x00 && b[2] === 0x01 && b[3] === 0x00) return 'image/x-icon'
  return null
}

/** 严格 UTF-8 可解码且控制字符占比低 → 视为文本 */
function sniffText(b: Uint8Array): boolean {
  let s: string
  try { s = new TextDecoder('utf-8', { fatal: true }).decode(b) } catch { return false }
  if (s.length === 0) return true
  let bad = 0
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c === 0x09 || c === 0x0a || c === 0x0d) continue
    if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) bad++
  }
  return bad / s.length < 0.1
}

const TEXT_MIMES = ['application/json', 'application/xml', 'application/yaml', 'application/x-yaml']

/** 由 base64（+ 可选 mime）推导预览：图片缩略图 / 文本内容 / 二进制下载 */
export function buildPreview(b64: string, mime: string | null): BinPreview {
  const bytes = b64ToBytes(b64)
  const imgType = sniffImage(bytes)
  if (imgType) return { kind: 'image', dataUrl: `data:${imgType};base64,${b64}`, size: bytes.length }
  const m = mime ?? ''
  if (m.startsWith('text/') || TEXT_MIMES.includes(m) || sniffText(bytes)) {
    let text = ''
    try { text = new TextDecoder('utf-8').decode(bytes) } catch { /* 按二进制处理 */ }
    const shown = text.length > 2000 ? `${text.slice(0, 2000)}\n…（已截断，共 ${bytes.length} 字节）` : text
    return { kind: 'text', text: shown, size: bytes.length }
  }
  return { kind: 'binary', dataUrl: `data:${m || 'application/octet-stream'};base64,${b64}`, size: bytes.length }
}

export function fmtSize(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
