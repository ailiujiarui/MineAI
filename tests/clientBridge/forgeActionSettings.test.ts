import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

import settings from '../../settings.ts'

test('Forge action settings default to the loopback emergency-stop channel', async () => {
  assert.equal(settings.forge_action.enabled, true)
  assert.equal(settings.forge_action.host, '127.0.0.1')
  assert.equal(settings.forge_action.port, 18765)
  assert.equal(settings.forge_action.ack_timeout_ms <= 3000, true)

  const spec = JSON.parse(await readFile('src/mindcraft/public/settings_spec.json', 'utf8'))
  assert.deepEqual(spec.forge_action.default, settings.forge_action)
})
