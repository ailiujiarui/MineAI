import test from 'node:test'
import assert from 'node:assert/strict'

import { createCombatNavigator } from '../../src/agent/library/combatNavigation.js'

test('combat navigator sets a follow goal when target is outside melee range', async () => {
  const calls: any[] = []
  const bot = {
    entity: {
      position: {
        distanceTo() {
          return 8
        }
      }
    },
    pathfinder: {
      setMovements(movements: any) {
        calls.push(['setMovements', movements])
      },
      setGoal(goal: any, dynamic: boolean) {
        calls.push(['setGoal', goal, dynamic])
      }
    }
  }

  const fakePf = {
    Movements: class {
      constructor(public botRef: any) {}
    },
    goals: {
      GoalFollow: class {
        constructor(public entity: any, public distance: number) {}
      }
    }
  }

  const navigator = createCombatNavigator(fakePf as any)
  const target = { position: {} }
  const moved = await navigator.moveWithinRange(bot as any, target as any, 3.2)

  assert.equal(moved, true)
  assert.equal(calls[0][0], 'setMovements')
  assert.equal(calls[1][0], 'setGoal')
  assert.equal(calls[1][2], true)
  assert.equal(calls[1][1].distance, 3.2)
})

test('combat navigator does not set a follow goal when target is already in range', async () => {
  const calls: any[] = []
  const bot = {
    entity: {
      position: {
        distanceTo() {
          return 2.5
        }
      }
    },
    pathfinder: {
      setMovements(movements: any) {
        calls.push(['setMovements', movements])
      },
      setGoal(goal: any, dynamic: boolean) {
        calls.push(['setGoal', goal, dynamic])
      }
    }
  }

  const fakePf = {
    Movements: class {
      constructor(public botRef: any) {}
    },
    goals: {
      GoalFollow: class {
        constructor(public entity: any, public distance: number) {}
      }
    }
  }

  const navigator = createCombatNavigator(fakePf as any)
  const target = { position: {} }
  const moved = await navigator.moveWithinRange(bot as any, target as any, 3.2)

  assert.equal(moved, false)
  assert.equal(calls.length, 0)
})

test('combat navigator uses an inverted follow goal to create space from close enemies', async () => {
  const calls: any[] = []
  const bot = {
    entity: {
      position: {
        distanceTo() {
          return 1.5
        }
      }
    },
    pathfinder: {
      setMovements(movements: any) {
        calls.push(['setMovements', movements])
      },
      setGoal(goal: any, dynamic: boolean) {
        calls.push(['setGoal', goal, dynamic])
      }
    }
  }

  const fakePf = {
    Movements: class {
      constructor(public botRef: any) {}
    },
    goals: {
      GoalFollow: class {
        constructor(public entity: any, public distance: number) {}
      },
      GoalInvert: class {
        constructor(public goal: any) {}
      }
    }
  }

  const navigator = createCombatNavigator(fakePf as any)
  const target = { position: {} }
  const moved = await navigator.moveAwayIfTooClose(bot as any, target as any, 2.2)

  assert.equal(moved, true)
  assert.equal(calls[1][0], 'setGoal')
  assert.equal(calls[1][1].goal.distance, 2.2)
  assert.equal(calls[1][2], true)
})
