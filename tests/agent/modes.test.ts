import test from 'node:test'
import assert from 'node:assert/strict'
import minecraftData from 'minecraft-data'

import { initModes } from '../../src/agent/modes.js'

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

function createAgentWithEnemy(distance: number) {
  const labels: string[] = []
  const enemy = {
    name: 'slime',
    type: 'mob',
    position: createPosition(distance, 64, 0)
  }

  const agent = {
    shut_up: true,
    isIdle() {
      return true
    },
    openChat() {},
    prompter: {
      getInitModes() {
        return {
          self_preservation: true,
          unstuck: false,
          cowardice: true,
          self_defense: true,
          hunting: false,
          item_collecting: false,
          torch_placing: false,
          elbow_room: false,
          idle_staring: false,
          cheat: false
        }
      }
    },
    self_prompter: {
      isActive() {
        return false
      },
      stopLoop() {}
    },
    actions: {
      currentActionLabel: '',
      resume_func: null,
      async runAction(actionLabel: string) {
        labels.push(actionLabel)
        return { success: true, message: '', interrupted: false, timedout: false }
      }
    },
    bot: {
      registry,
      entities: {
        enemy
      },
      entity: {
        position: {
          x: 0,
          y: 64,
          z: 0,
          offset(dx: number, dy: number, dz: number) {
            return {
              x: this.x + dx,
              y: this.y + dy,
              z: this.z + dz
            }
          },
          distanceTo(target: { x: number; y: number; z: number }) {
            return createPosition(this.x, this.y, this.z).distanceTo(target)
          }
        }
      },
      blockAt() {
        return { name: 'air' }
      },
      nearestEntity(predicate?: (entity: unknown) => boolean) {
        if (!predicate) {
          return null
        }
        return predicate(enemy) ? enemy : null
      },
      clearControlStates() {},
      inventory: {
        emptySlotCount() {
          return 0
        }
      },
      pathfinder: {
        async getPathTo() {
          return { status: 'success' }
        }
      },
      output: '',
      interrupt_code: false
    }
  }

  initModes(agent as any)
  return { agent, labels }
}

test('self defense takes priority over cowardice for nearby hostiles', async () => {
  const { agent, labels } = createAgentWithEnemy(4)

  await agent.bot.modes.update()

  assert.equal(labels[0], 'mode:self_defense')
})

test('cowardice handles hostiles that are outside self defense range', async () => {
  const { agent, labels } = createAgentWithEnemy(12)

  await agent.bot.modes.update()

  assert.equal(labels[0], 'mode:cowardice')
})
