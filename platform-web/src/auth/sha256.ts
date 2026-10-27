/**
 * 纯 JS SHA-256。
 *
 * <p>存在的唯一理由：`crypto.subtle` 只在**安全上下文**（https / localhost / 127.0.0.1 /
 * file:）才暴露。本平台按内网 http 部署（见 platform-web/nginx.conf 的 80 端口），用局域网 IP
 * 打开时 `crypto.subtle` 是 `undefined`，PKCE 的 code_challenge 就算不出来 —— 表现为点「登录」
 * 直接报 `Cannot read properties of undefined (reading 'digest')`（`oauth.ts` 的 sha256Challenge）。
 *
 * <p>PKCE 的 verifier 是一次性随机串（几秒内换完即弃），用 JS 实现算摘要不引入额外风险；
 * 安全上下文可用时仍优先走 `crypto.subtle`（见 oauth.ts）。
 */

/** SHA-256 轮常量（FIPS 180-4 §4.2.2） */
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
])

/** 32 位循环右移 */
function rotr(x: number, n: number): number {
  return ((x >>> n) | (x << (32 - n))) >>> 0
}

/** 定长数组按下标取值：索引恒在界内，这里只是绕开 noUncheckedIndexedAccess 的 number|undefined */
function at(arr: Uint32Array, i: number): number {
  return arr[i] as number
}

/**
 * 计算 SHA-256 摘要。
 * @param data 原始字节（UTF-8 编码由调用方负责，见 oauth.ts 的 TextEncoder）
 * @returns 32 字节摘要
 */
export function sha256(data: Uint8Array): Uint8Array {
  // 填充：0x80 + 若干 0x00 + 8 字节大端比特长度，总长补齐到 64 的整数倍
  const paddedLen = Math.ceil((data.length + 9) / 64) * 64
  const buf = new Uint8Array(paddedLen)
  buf.set(data)
  buf[data.length] = 0x80
  const view = new DataView(buf.buffer)
  const bitLen = data.length * 8
  view.setUint32(paddedLen - 8, Math.floor(bitLen / 0x100000000))
  view.setUint32(paddedLen - 4, bitLen >>> 0)

  const H = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ])
  const W = new Uint32Array(64)

  for (let off = 0; off < paddedLen; off += 64) {
    for (let i = 0; i < 16; i++) {
      W[i] = view.getUint32(off + i * 4)
    }
    for (let i = 16; i < 64; i++) {
      const w15 = at(W, i - 15)
      const w2 = at(W, i - 2)
      const s0 = rotr(w15, 7) ^ rotr(w15, 18) ^ (w15 >>> 3)
      const s1 = rotr(w2, 17) ^ rotr(w2, 19) ^ (w2 >>> 10)
      W[i] = (at(W, i - 16) + s0 + at(W, i - 7) + s1) >>> 0
    }

    let a = at(H, 0), b = at(H, 1), c = at(H, 2), d = at(H, 3)
    let e = at(H, 4), f = at(H, 5), g = at(H, 6), h = at(H, 7)

    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const t1 = (h + S1 + ch + at(K, i) + at(W, i)) >>> 0
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const t2 = (S0 + maj) >>> 0

      h = g
      g = f
      f = e
      e = (d + t1) >>> 0
      d = c
      c = b
      b = a
      a = (t1 + t2) >>> 0
    }

    H[0] = (at(H, 0) + a) >>> 0
    H[1] = (at(H, 1) + b) >>> 0
    H[2] = (at(H, 2) + c) >>> 0
    H[3] = (at(H, 3) + d) >>> 0
    H[4] = (at(H, 4) + e) >>> 0
    H[5] = (at(H, 5) + f) >>> 0
    H[6] = (at(H, 6) + g) >>> 0
    H[7] = (at(H, 7) + h) >>> 0
  }

  const out = new Uint8Array(32)
  const outView = new DataView(out.buffer)
  for (let i = 0; i < 8; i++) {
    outView.setUint32(i * 4, at(H, i))
  }
  return out
}
