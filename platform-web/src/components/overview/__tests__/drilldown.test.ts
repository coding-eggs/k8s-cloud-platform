import assert from 'node:assert/strict'
import { test } from 'node:test'
import { alertTargetsOf, nsTargetsOf } from '../labels.ts'
import type { AlertBreakdown } from '@/types/overview'

test('alert drills retain the cluster of repeated namespace names and filter by severity', () => {
  const alerts: AlertBreakdown = {
    total: 6, byStatus: { CRITICAL: 2, WARNING: 4 },
    byNamespace: [
      { clusterId: 'a', namespace: 'demo', total: 4, byStatus: { WARNING: 4 } },
      { clusterId: 'b', namespace: 'demo', total: 1, byStatus: { CRITICAL: 1 } },
      { clusterId: 'b', namespace: null, total: 1, byStatus: { CRITICAL: 1 } },
    ],
  }
  const targets = (severity?: string) => alertTargetsOf(alerts, severity)
    .map(({ clusterId, namespace, count }) => [clusterId, namespace, count])
  assert.deepEqual(targets(), [['a', 'demo', 4], ['b', 'demo', 1]])
  assert.deepEqual(targets('critical'), [['b', 'demo', 1]])
  assert.deepEqual(targets('warning'), [['a', 'demo', 4]])
})

test('whole Pod and workload cards locate namespaces with matching resources', () => {
  const rows = [
    { clusterId: 'a', namespace: 'demo', running: 2, pending: 1, succeeded: 0, abnormal: 1, workloads: 3, unhealthyWorkloads: 0 },
    { clusterId: 'b', namespace: 'demo', running: 0, pending: 0, succeeded: 0, abnormal: 0, workloads: 1, unhealthyWorkloads: 1 },
  ]
  const total = { label: '总数', value: 4, tone: 'info' as const }
  const targets = (kind: 'pod' | 'workload') => nsTargetsOf(kind, total, rows)
    ?.map(({ clusterId, namespace, count }) => [clusterId, namespace, count])
  assert.deepEqual(targets('pod'), [['a', 'demo', 4]])
  assert.deepEqual(targets('workload'), [['a', 'demo', 3], ['b', 'demo', 1]])
})
