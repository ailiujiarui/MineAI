import test from 'node:test'
import assert from 'node:assert/strict'
import minecraftData from 'minecraft-data'

import { selectHostileTarget } from '../../src/agent/library/world.js'
import { defendSelf } from '../../src/agent/library/skills.js'

const registry = minecraftData('1.20.1')

function createPosition(x: number, y: number, z: number) {
  return {
    x,
    y,
    z,
    distanceTo(target: { x: number; y: number; z: number }) {
      const dx = this.x - target.x
      const dy = this.y - target.y
      const dz = this.z - target.z
      return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }
  }
}

test('selectHostileTarget skips blocked nearest hostile and picks a reachable one', async () => {
  const blocked = { name: 'slime', type: 'mob', position: createPosition(2, 64, 0) }
  const reachable = { name: 'zombie', type: 'mob', position: createPosition(4, 64, 0) }
  const bot = {
    entity: {
      position: createPosition(0, 64, 0)
    },
    entities: {
      blocked,
      reachable
    }
  }

  const target = await selectHostileTarget(bot as any, 8, {}, {
    isClearPath: async (_bot: unknown, entity: unknown) => entity === reachable
  })

  assert.equal(target, reachable)
})

test('selectHostileTarget honors a locked target while it is still valid', async () => {
  const locked = { name: 'zombie', type: 'mob', position: createPosition(4, 64, 0) }
  const closer = { name: 'slime', type: 'mob', position: createPosition(2, 64, 0) }
  const bot = {
    entity: {
      position: createPosition(0, 64, 0)
    },
    entities: {
      locked,
      closer
    }
  }

  const target = await selectHostileTarget(bot as any, 8, {
    currentTarget: locked as any,
    currentTargetExpiresAt: 5_000
  }, {
    now: () => 4_000,
    isClearPath: async () => true
  })

  assert.equal(target, locked)
})

test('defendSelf keeps passing the current target back into selection while locked', async () => {
  const recordedOptions: Array<{ currentTarget: unknown; currentTargetExpiresAt: number }> = []
  const target = { name: 'zombie', type: 'mob', position: createPosition(4, 64, 0) }
  let selectionCount = 0
  let now = 1_000

  const bot = {
    registry,
    entity: {
      position: createPosition(0, 64, 0)
    },
    inventory: {
      items() {
        return []
      }
    },
    modes: {
      pause() {}
    },
    pathfinder: {
      setMovements() {},
      setGoal() {},
      stop() {}
    },
    pvp: {
      attack() {},
      stop() {}
    },
    output: '',
    interrupt_code: false
  }

  const attacked = await defendSelf(bot as any, 8, {
    now: () => {
      now += 100
      return now
    },
    sleep: async () => {},
    selectHostileTarget: async (_bot: unknown, _range: number, options: { currentTarget: unknown; currentTargetExpiresAt: number }) => {
      recordedOptions.push(options)
      selectionCount += 1
      if (selectionCount === 1) return target
      if (selectionCount === 2) return target
      return null
    }
  })

  assert.equal(attacked, true)
  assert.equal(recordedOptions[0].currentTarget, null)
  assert.equal(recordedOptions[1].currentTarget, target)
  assert.ok(recordedOptions[1].currentTargetExpiresAt > 0)
})
