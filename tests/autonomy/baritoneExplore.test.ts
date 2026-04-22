import test from 'node:test'
import assert from 'node:assert/strict'
import { chooseExploreTarget } from '../../src/autonomy/baritoneExplore.js'

test('chooseExploreTarget expands outward from the current position', () => {
  const target = chooseExploreTarget(
    { x: 100, y: 64, z: 200 },
    {
      radius: 32,
      exploredWaypoints: []
    }
  )

  assert.equal(typeof target.x, 'number')
  assert.equal(typeof target.z, 'number')
  assert.notEqual(`${target.x},${target.z}`, '100,200')
})

test('chooseExploreTarget skips already explored waypoints', () => {
  const first = chooseExploreTarget(
    { x: 0, y: 64, z: 0 },
    {
      radius: 32,
      exploredWaypoints: []
    }
  )
  const second = chooseExploreTarget(
    { x: 0, y: 64, z: 0 },
    {
      radius: 32,
      exploredWaypoints: [`${first.x},${first.z}`]
    }
  )

  assert.notDeepEqual(first, second)
})
