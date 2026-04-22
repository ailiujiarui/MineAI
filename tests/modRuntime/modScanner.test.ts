import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm, readFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

import { scanModDirectory } from '../../src/modRuntime/modScanner.js'

const execFileAsync = promisify(execFile)

async function createForgeJar(modsDir: string, jarName: string, tomlBody: string) {
  const tempRoot = await mkdtemp(path.join(os.tmpdir(), 'mod-fixture-'))
  const metaInfDir = path.join(tempRoot, 'META-INF')
  await mkdir(metaInfDir, { recursive: true })
  await writeFile(path.join(metaInfDir, 'mods.toml'), tomlBody, 'utf8')

  const jarPath = path.join(modsDir, jarName)
  const zipPath = `${jarPath}.zip`
  await execFileAsync('powershell.exe', [
    '-NoProfile',
    '-Command',
    `Compress-Archive -Path '${tempRoot.replace(/'/g, "''")}\\*' -DestinationPath '${zipPath.replace(/'/g, "''")}' -Force`
  ])
  await writeFile(jarPath, await readFile(zipPath))
  await rm(zipPath, { force: true })
  await rm(tempRoot, { recursive: true, force: true })
  return jarPath
}

test('scanModDirectory reads forge metadata and capability tags from jars', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'mods-dir-'))
  const modsDir = path.join(root, 'mods')
  await mkdir(modsDir, { recursive: true })

  try {
    await createForgeJar(
      modsDir,
      'epicfight-forge.jar',
      [
        'modLoader="javafml"',
        'loaderVersion="[47,)"',
        'license="GPL-3.0"',
        '',
        '[[mods]]',
        'modId="epicfight"',
        'displayName="Epic Fight"',
        'version="20.9.7"'
      ].join('\n')
    )

    await createForgeJar(
      modsDir,
      'slashblade.jar',
      [
        'modLoader="javafml"',
        'loaderVersion="[47,)"',
        'license="MIT"',
        '',
        '[[mods]]',
        'modId="slashblade"',
        'displayName="SlashBlade"',
        'version="1.0.0"'
      ].join('\n')
    )

    const result = await scanModDirectory(modsDir)

    assert.equal(result.mods.length, 2)
    assert.equal(result.capabilities.hasEpicFight, true)
    assert.equal(result.capabilities.hasSlashBlade, true)
    assert.deepEqual(result.capabilities.tags, ['combat.epic_fight', 'weapon.slashblade'])
  } finally {
    try {
      await rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
    } catch {}
  }
})

test('scanModDirectory falls back to jar name when metadata is incomplete', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'mods-dir-'))
  const modsDir = path.join(root, 'mods')
  await mkdir(modsDir, { recursive: true })

  try {
    await createForgeJar(
      modsDir,
      'touhou_little_maid-1.0.0.jar',
      [
        'modLoader="javafml"',
        'loaderVersion="[47,)"',
        'license="MIT"'
      ].join('\n')
    )

    const result = await scanModDirectory(modsDir)

    assert.equal(result.mods[0].modId, 'touhou_little_maid')
    assert.equal(result.capabilities.tags.includes('maid.touhou_little_maid'), true)
  } finally {
    try {
      await rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
    } catch {}
  }
})
