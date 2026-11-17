import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildZip, crc32 } from '../zip.ts'

const enc = new TextEncoder()

function u16(b: Uint8Array, o: number): number {
  return b[o] | (b[o + 1] << 8)
}
function u32(b: Uint8Array, o: number): number {
  return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0
}

test('crc32 对已知向量正确', () => {
  assert.equal(crc32(enc.encode('123456789')), 0xcbf43926)
  assert.equal(crc32(new Uint8Array()), 0)
})

test('生成的 ZIP 有正确的本地头、中央目录与 EOCD 计数', async () => {
  const blob = buildZip([
    { path: 'inventory/demo/hosts.yaml', content: 'all:\n  hosts:\n' },
    { path: 'inventory/demo/README.md', content: '# hi' },
  ])
  const bytes = new Uint8Array(await blob.arrayBuffer())

  assert.equal(u32(bytes, 0), 0x04034b50, '本地文件头签名')
  const eocd = bytes.slice(bytes.length - 22)
  assert.equal(u32(eocd, 0), 0x06054b50, 'EOCD 签名')
  assert.equal(u16(eocd, 10), 2, '文件数')

  const text = new TextDecoder('latin1').decode(bytes)
  assert.ok(text.includes('inventory/demo/hosts.yaml'))
  assert.ok(text.includes('inventory/demo/README.md'))
  assert.ok(text.includes('all:\n  hosts:\n'), '内容按原样写入')
})
