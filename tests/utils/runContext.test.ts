import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { getRunId, getRunRoot, initRunContext } from '../../src/utils/runContext.js'

test('run context prefers an inherited parent root', () => {
  const tempRoot = mkdtempSync(path.join(tmpdir(), 'mineai-run-context-'))
  const inheritedRoot = path.join(tempRoot, 'parent-lifecycle')

  try {
    assert.equal(initRunContext(path.join(tempRoot, 'ignored-base'), inheritedRoot), inheritedRoot)
    assert.equal(getRunRoot(), inheritedRoot)
    assert.equal(getRunId(), 'parent-lifecycle')
  } finally {
    rmSync(tempRoot, { recursive: true, force: true })
  }
})
