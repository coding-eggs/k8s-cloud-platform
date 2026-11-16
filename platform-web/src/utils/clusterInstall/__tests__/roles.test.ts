import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanupEtcdRoles } from '../roles.ts'
import type { InstallNode } from '../types.ts'

function node(hostname: string, roles: InstallNode['roles']): InstallNode {
  return { hostname, ip: '10.0.0.1', sshPort: 22, sshUser: 'root', os: 'rocky9', roles }
}

test('专用 etcd 模式：原样返回，不做任何清理', () => {
  const nodes = [node('cp-1', ['control-plane', 'etcd'])]
  const r = cleanupEtcdRoles(nodes, true)
  assert.equal(r.nodes, nodes)
  assert.equal(r.stripped, 0)
  assert.equal(r.dropped, 0)
})

test('切走专用：兼做控制面的去掉 etcd，只做 etcd 的整行移除', () => {
  const r = cleanupEtcdRoles(
    [node('cp-1', ['control-plane', 'etcd']), node('etcd-1', ['etcd']), node('w-1', ['worker'])],
    false,
  )
  assert.deepEqual(r.nodes.map((n) => `${n.hostname}:${n.roles.join('+')}`), ['cp-1:control-plane', 'w-1:worker'])
  assert.equal(r.stripped, 1)
  assert.equal(r.dropped, 1)
})

test('没有 etcd 角色时不动任何行', () => {
  const r = cleanupEtcdRoles([node('cp-1', ['control-plane']), node('w-1', ['worker'])], false)
  assert.equal(r.nodes.length, 2)
  assert.equal(r.stripped, 0)
  assert.equal(r.dropped, 0)
})
