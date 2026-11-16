import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseNodeRows } from '../importNodes.ts'

test('从 Excel 粘的制表符行：hostname / IP 两列即可，其余取默认', () => {
  const r = parseNodeRows('cp-1\t10.0.0.11\ncp-2\t10.0.0.12\nw-1\t10.0.0.21')
  assert.equal(r.issues.length, 0)
  assert.deepEqual(r.nodes, [
    { hostname: 'cp-1', ip: '10.0.0.11', sshPort: 22, sshUser: 'root', os: 'rocky9', roles: ['worker'] },
    { hostname: 'cp-2', ip: '10.0.0.12', sshPort: 22, sshUser: 'root', os: 'rocky9', roles: ['worker'] },
    { hostname: 'w-1', ip: '10.0.0.21', sshPort: 22, sshUser: 'root', os: 'rocky9', roles: ['worker'] },
  ])
})

test('逗号 CSV + 表头行：表头被跳过', () => {
  const r = parseNodeRows('hostname,ip,ssh_port,ssh_user,os,roles\ncp-1,10.0.0.11,2222,ubuntu,ubuntu2204,control-plane')
  assert.equal(r.issues.length, 0)
  assert.deepEqual(r.nodes[0], {
    hostname: 'cp-1',
    ip: '10.0.0.11',
    sshPort: 2222,
    sshUser: 'ubuntu',
    os: 'ubuntu2204',
    roles: ['control-plane'],
  })
})

test('六列全给：角色可多选（/ 分隔），OS 也认中文名', () => {
  const r = parseNodeRows('etcd-1\t10.0.0.31\t22\troot\tRocky Linux 9\tetcd/worker')
  assert.equal(r.issues.length, 0)
  assert.deepEqual(r.nodes[0].roles, ['etcd', 'worker'])
  assert.equal(r.nodes[0].os, 'rocky9')
})

test('角色同义词：master → control-plane，cp → control-plane', () => {
  const r = parseNodeRows('a\t10.0.0.11\t22\troot\trocky9\tmaster\nb\t10.0.0.12\t22\troot\trocky9\tcp')
  assert.equal(r.issues.length, 0)
  assert.deepEqual(r.nodes.map((n) => n.roles), [['control-plane'], ['control-plane']])
})

test('空白行、注释行与首尾空白被忽略', () => {
  const r = parseNodeRows('\n# 这是注释\n  cp-1\t10.0.0.11  \n\n')
  assert.equal(r.issues.length, 0)
  assert.equal(r.nodes.length, 1)
  assert.equal(r.nodes[0].hostname, 'cp-1')
})

test('空文本 → 0 节点 0 错误', () => {
  const r = parseNodeRows('   \n\n')
  assert.deepEqual(r.nodes, [])
  assert.deepEqual(r.issues, [])
})

test('IP 不合法：给出行号，不产出该行', () => {
  const r = parseNodeRows('cp-1\t10.0.0.11\ncp-2\t10.0.0.999')
  assert.equal(r.nodes.length, 1)
  assert.equal(r.issues.length, 1)
  assert.equal(r.issues[0].line, 2)
  assert.match(r.issues[0].message, /IP/)
})

test('导入内部 IP / hostname 重复：报重复', () => {
  const r = parseNodeRows('cp-1\t10.0.0.11\ncp-1\t10.0.0.12\ncp-2\t10.0.0.11')
  assert.equal(r.nodes.length, 1)
  assert.equal(r.issues.length, 2)
  assert.match(r.issues.map((i) => i.message).join('|'), /hostname 重复/)
  assert.match(r.issues.map((i) => i.message).join('|'), /IP 重复/)
})

test('未知角色 / 未知 OS / 端口非法都给出带行号的错误', () => {
  const r = parseNodeRows(
    ['a\t10.0.0.11\t22\troot\trocky9\tboss', 'b\t10.0.0.12\t22\troot\tplan9\tworker', 'c\t10.0.0.13\t70000\troot\trocky9\tworker'].join('\n'),
  )
  assert.equal(r.nodes.length, 0)
  assert.deepEqual(r.issues.map((i) => i.line), [1, 2, 3])
  assert.match(r.issues[0].message, /角色/)
  assert.match(r.issues[1].message, /操作系统/)
  assert.match(r.issues[2].message, /端口/)
})

test('列数超过 6 报错，避免把一整行 Excel 静默截断', () => {
  const r = parseNodeRows('a\t10.0.0.11\t22\troot\trocky9\tworker\t多余')
  assert.equal(r.nodes.length, 0)
  assert.equal(r.issues.length, 1)
  assert.match(r.issues[0].message, /列/)
})

test('hostname 不合法（空格已被制表符切开，这里测非法字符）', () => {
  const r = parseNodeRows('cp#1\t10.0.0.11')
  assert.equal(r.nodes.length, 0)
  assert.equal(r.issues.length, 1)
  assert.match(r.issues[0].message, /hostname/)
})
