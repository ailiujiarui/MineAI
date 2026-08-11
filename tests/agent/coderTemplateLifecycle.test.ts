import test from 'node:test'
import assert from 'node:assert/strict'

import { Coder } from '../../src/agent/coder.js'

function createCoder(options: Record<string, unknown>) {
  return new Coder({ name: `coder-template-${Date.now()}-${Math.random()}` }, options)
}

test('Coder skips missing templates when insecure coding is disabled', async () => {
  const coder = createCoder({
    allowInsecureCoding: false,
    execTemplatePath: './missing/execTemplate.ts',
    lintTemplatePath: './missing/lintTemplate.ts'
  })

  assert.deepEqual(await coder.initializeTemplates(), {
    enabled: false,
    ready: false,
    error: null
  })
})

test('Coder reports missing templates only when insecure coding is invoked', async () => {
  const coder = createCoder({
    allowInsecureCoding: true,
    execTemplatePath: './missing/execTemplate.ts',
    lintTemplatePath: './missing/lintTemplate.ts'
  })

  const state = await coder.initializeTemplates()
  assert.equal(state.enabled, true)
  assert.equal(state.ready, false)
  assert.ok(state.error instanceof Error)
  await assert.rejects(
    () => coder.generateCode({}),
    /Dynamic code templates could not be loaded/
  )
})
