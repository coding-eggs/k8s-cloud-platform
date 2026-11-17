/**
 * 极小的 ZIP 打包器（store 方式，不压缩），只为「一次性下载整个部署包」。
 *
 * <p>不引依赖：ZIP 的 store 格式就是「本地文件头 + 原始字节 + 中央目录 + 结尾记录」，
 * 只要 CRC32 算对，Windows / macOS / unzip / bsdtar 都能直接打开。
 * 我们的产物只有几十 KB，不压缩也无所谓。
 */

const CRC_TABLE: Uint32Array = (() => {
  const table = new Uint32Array(256)
  for (let i = 0; i < 256; i++) {
    let c = i
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[i] = c >>> 0
  }
  return table
})()

export function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff
  for (let i = 0; i < bytes.length; i++) {
    const idx = (c ^ (bytes[i] ?? 0)) & 0xff
    c = (CRC_TABLE[idx] ?? 0) ^ (c >>> 8)
  }
  return (c ^ 0xffffffff) >>> 0
}

export interface ZipEntry {
  /** 压缩包内路径，例如 `inventory/demo/hosts.yaml` */
  path: string
  content: string
}

const LOCAL_SIG = 0x04034b50
const CENTRAL_SIG = 0x02014b50
const EOCD_SIG = 0x06054b50
/** 0x0800：文件名按 UTF-8 解释（我们的路径本来就是 ASCII，写上更保险） */
const FLAG_UTF8 = 0x0800
/** 1980-01-01，固定时间戳让同样的输入产出同样的字节 */
const DOS_TIME = 0
const DOS_DATE = 33

export function buildZip(entries: ZipEntry[]): Blob {
  const encoder = new TextEncoder()
  const locals: Uint8Array[] = []
  const centrals: Uint8Array[] = []
  let offset = 0

  for (const entry of entries) {
    const name = encoder.encode(entry.path)
    const data = encoder.encode(entry.content)
    const sum = crc32(data)

    const local = new Uint8Array(30 + name.length + data.length)
    const lv = new DataView(local.buffer)
    lv.setUint32(0, LOCAL_SIG, true)
    lv.setUint16(4, 20, true) // 需要解压版本 2.0
    lv.setUint16(6, FLAG_UTF8, true)
    lv.setUint16(8, 0, true) // 0 = store
    lv.setUint16(10, DOS_TIME, true)
    lv.setUint16(12, DOS_DATE, true)
    lv.setUint32(14, sum, true)
    lv.setUint32(18, data.length, true)
    lv.setUint32(22, data.length, true)
    lv.setUint16(26, name.length, true)
    lv.setUint16(28, 0, true)
    local.set(name, 30)
    local.set(data, 30 + name.length)
    locals.push(local)

    const central = new Uint8Array(46 + name.length)
    const cv = new DataView(central.buffer)
    cv.setUint32(0, CENTRAL_SIG, true)
    cv.setUint16(4, 20, true) // version made by
    cv.setUint16(6, 20, true) // version needed
    cv.setUint16(8, FLAG_UTF8, true)
    cv.setUint16(10, 0, true)
    cv.setUint16(12, DOS_TIME, true)
    cv.setUint16(14, DOS_DATE, true)
    cv.setUint32(16, sum, true)
    cv.setUint32(20, data.length, true)
    cv.setUint32(24, data.length, true)
    cv.setUint16(28, name.length, true)
    cv.setUint16(30, 0, true) // extra
    cv.setUint16(32, 0, true) // comment
    cv.setUint16(34, 0, true) // disk
    cv.setUint16(36, 0, true) // internal attrs
    cv.setUint32(38, 0, true) // external attrs
    cv.setUint32(42, offset, true) // local header offset
    central.set(name, 46)
    centrals.push(central)

    offset += local.length
  }

  const centralSize = centrals.reduce((n, c) => n + c.length, 0)
  const eocd = new Uint8Array(22)
  const ev = new DataView(eocd.buffer)
  ev.setUint32(0, EOCD_SIG, true)
  ev.setUint16(4, 0, true)
  ev.setUint16(6, 0, true)
  ev.setUint16(8, entries.length, true)
  ev.setUint16(10, entries.length, true)
  ev.setUint32(12, centralSize, true)
  ev.setUint32(16, offset, true)
  ev.setUint16(20, 0, true)

  // 拼成一段连续字节再交给 Blob：分段 Uint8Array 在 TS 5.7 的 BlobPart 里不被接受
  const parts = [...locals, ...centrals, eocd]
  const total = parts.reduce((n, p) => n + p.length, 0)
  const out = new Uint8Array(total)
  let pos = 0
  for (const part of parts) {
    out.set(part, pos)
    pos += part.length
  }
  return new Blob([out.buffer as ArrayBuffer], { type: 'application/zip' })
}
