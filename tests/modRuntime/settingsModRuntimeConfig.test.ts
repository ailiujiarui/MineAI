import test from 'node:test'
import assert from 'node:assert/strict'

import settings from '../../settings.ts'

test('settings expose mod runtime auto-detection and combat override defaults', () => {
  assert.equal(settings.mod_runtime.enabled, true)
  assert.equal(settings.mod_runtime.instance_mode, 'launcher')
  assert.equal(settings.mod_runtime.target_minecraft_version, '1.20.1')
  assert.equal(settings.mod_runtime.target_loader, 'forge')
  assert.equal(settings.combat.mode, 'auto')
  assert.deepEqual(settings.combat.epicfight.weapon_profiles, ['sword', 'axe', 'slashblade'])
})
