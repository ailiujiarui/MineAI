import test from 'node:test'
import assert from 'node:assert/strict'

import { deriveModCapabilities } from '../../src/modRuntime/modKnowledge.js'

test('deriveModCapabilities recognizes epic fight and slashblade', () => {
  const capabilities = deriveModCapabilities([
    {
      modId: 'epicfight',
      displayName: 'Epic Fight',
      sourceJar: 'epicfight-forge-20.9.7.jar'
    },
    {
      modId: 'slashblade',
      displayName: 'SlashBlade Resharped',
      sourceJar: 'slashblade.jar'
    }
  ])

  assert.equal(capabilities.hasEpicFight, true)
  assert.equal(capabilities.hasSlashBlade, true)
  assert.deepEqual(capabilities.tags, ['combat.epic_fight', 'weapon.slashblade'])
})

test('deriveModCapabilities tags touhou little maid separately from combat mods', () => {
  const capabilities = deriveModCapabilities([
    {
      modId: 'touhou_little_maid',
      displayName: 'Touhou Little Maid',
      sourceJar: 'touhou_little_maid.jar'
    }
  ])

  assert.equal(capabilities.hasEpicFight, false)
  assert.equal(capabilities.hasSlashBlade, false)
  assert.deepEqual(capabilities.tags, ['maid.touhou_little_maid'])
})
