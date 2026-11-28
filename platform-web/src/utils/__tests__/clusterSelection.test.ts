import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveClusterSelection } from '../clusterSelection.ts'

const disabled = { clusterId: 'disabled', clusterName: 'Disabled', enabled: 0 }
const management = { clusterId: 'management', clusterName: 'Management', enabled: 1 }
const testCluster = { clusterId: 'test', clusterName: 'Test', enabled: 1 }
const clusters = [disabled, management, testCluster]

test('keeps the enabled cluster from the URL or a returning detail page', () => {
  assert.deepEqual(resolveClusterSelection(clusters, 'test'), {
    cluster: testCluster,
    unavailable: false,
  })
})

test('uses the first enabled default without a warning for a plain list URL', () => {
  assert.deepEqual(resolveClusterSelection(clusters, ''), {
    cluster: management,
    unavailable: false,
  })
})

test('marks disabled, removed and inaccessible cluster selections for fallback', () => {
  for (const clusterId of ['disabled', 'unavailable']) {
    assert.deepEqual(resolveClusterSelection(clusters, clusterId), {
      cluster: management,
      unavailable: true,
    })
  }
})

test('selects no cluster when all registered clusters are disabled or absent', () => {
  assert.deepEqual(resolveClusterSelection([disabled], 'disabled'), {
    cluster: undefined,
    unavailable: true,
  })
  assert.deepEqual(resolveClusterSelection([], ''), {
    cluster: undefined,
    unavailable: false,
  })
})
