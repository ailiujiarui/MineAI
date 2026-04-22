import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

import {
  DEFAULT_FORGE_AGENT_JAR_NAME,
  deployForgeAgentJar,
  resolveForgeAgentJarPath
} from '../../src/forgeAgent/deploySupport.js'

test('resolveForgeAgentJarPath points at the built forge-agent jar by default', () => {
  const resolved = resolveForgeAgentJarPath('F:/computer/java_ximu/game-ai')

  assert.equal(
    resolved.replace(/\\/g, '/'),
    'F:/computer/java_ximu/game-ai/forge-agent/build/libs/' + DEFAULT_FORGE_AGENT_JAR_NAME
  )
})

test('deployForgeAgentJar copies the built jar into the target mods directory', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'forge-agent-deploy-'))
  const libsDir = path.join(root, 'forge-agent', 'build', 'libs')
  const modsDir = path.join(root, 'instance', '.minecraft', 'mods')
  const sourceJar = path.join(libsDir, DEFAULT_FORGE_AGENT_JAR_NAME)

  try {
    await mkdir(libsDir, { recursive: true })
    await mkdir(modsDir, { recursive: true })
    await writeFile(sourceJar, Buffer.from('forge-agent-binary'))

    const deployed = await deployForgeAgentJar({
      workspaceRoot: root,
      modsDir
    })

    assert.equal(deployed.targetPath, path.join(modsDir, DEFAULT_FORGE_AGENT_JAR_NAME))
    assert.deepEqual(await readFile(deployed.targetPath), Buffer.from('forge-agent-binary'))
  } finally {
    await rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }
})
