import assert from 'node:assert/strict'
import test from 'node:test'
import { clusterConnectionStatus } from '../format.ts'

test('a disabled cluster does not show its last connected state as live', () => {
  assert.deepEqual(clusterConnectionStatus(0, 'CONNECTED'), { label: '未探测', type: 'info' })
})

test('an enabled cluster retains the connection status', () => {
  assert.deepEqual(clusterConnectionStatus(1, 'CONNECTED'), { label: '已连接', type: 'success' })
  assert.deepEqual(clusterConnectionStatus(1, 'ERROR'), { label: '错误', type: 'danger' })
})
