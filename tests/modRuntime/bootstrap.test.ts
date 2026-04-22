import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

import { resolveModRuntimeTarget } from '../../src/modRuntime/bootstrap.js'

test('resolveModRuntimeTarget prefers explicit mods directory', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'mod-runtime-target-'))

  try {
    const modsDir = path.join(root, 'mods')
    await mkdir(modsDir, { recursive: true })

    const target = await resolveModRuntimeTarget({
      modsDir,
      targetVersion: '1.20.1',
      targetLoader: 'forge'
    })

    assert.equal(target?.modsDir, modsDir)
    assert.equal(target?.selectedInstance, null)
  } finally {
    await rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }
})

test('resolveModRuntimeTarget resolves gameDir-like instance input into a mods directory', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'mod-runtime-target-'))

  try {
    const gameDir = path.join(root, '.minecraft')
    const modsDir = path.join(gameDir, 'mods')
    await mkdir(modsDir, { recursive: true })

    const target = await resolveModRuntimeTarget({
      instanceDir: gameDir,
      targetVersion: '1.20.1',
      targetLoader: 'forge'
    })

    assert.equal(target?.modsDir, modsDir)
    assert.equal(target?.selectedInstance?.launcherType, 'manual')
  } finally {
    await rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }
})

test('resolveModRuntimeTarget selects a preferred prism launcher instance from launcher roots', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'prism-runtime-target-'))

  try {
    const instanceDir = path.join(root, 'instances', 'EpicFightPack')
    const gameDir = path.join(instanceDir, '.minecraft')
    const modsDir = path.join(gameDir, 'mods')
    await mkdir(modsDir, { recursive: true })
    await writeFile(path.join(instanceDir, 'instance.cfg'), 'name=Epic Fight Pack\n')
    await writeFile(path.join(instanceDir, 'mmc-pack.json'), JSON.stringify({
      components: [
        { uid: 'net.minecraft', version: '1.20.1' },
        { uid: 'net.minecraftforge', version: '47.2.0' }
      ]
    }))

    const target = await resolveModRuntimeTarget({
      launcherRoots: [{ launcherType: 'prism', rootDir: root }],
      targetVersion: '1.20.1',
      targetLoader: 'forge'
    })

    assert.equal(target?.modsDir, modsDir)
    assert.equal(target?.selectedInstance?.name, 'Epic Fight Pack')
  } finally {
    await rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }
})
