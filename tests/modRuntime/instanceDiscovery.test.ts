import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

import { discoverLauncherInstances, getDefaultLauncherRoots, normalizeLauncherInstance, selectPreferredLauncherInstance } from '../../src/modRuntime/instanceDiscovery.js'

test('normalizeLauncherInstance keeps only supported forge 1.20.1 launcher instances', () => {
  const instance = normalizeLauncherInstance({
    name: 'Epic Fight Pack',
    gameDir: 'F:/Instances/EpicFightPack/.minecraft',
    version: '1.20.1',
    loader: 'forge',
    launcherType: 'launcher'
  })

  assert.equal(instance.name, 'Epic Fight Pack')
  assert.equal(instance.launchable, true)
  assert.equal(instance.version, '1.20.1')
  assert.equal(instance.loader, 'forge')
  assert.equal(instance.gameDir, 'F:/Instances/EpicFightPack/.minecraft')
})

test('normalizeLauncherInstance marks unsupported loaders as not launchable', () => {
  const instance = normalizeLauncherInstance({
    name: 'Unknown Pack',
    gameDir: 'F:/Instances/UnknownPack/.minecraft',
    version: '1.20.1',
    loader: 'fabric',
    launcherType: 'launcher'
  })

  assert.equal(instance.launchable, false)
})

test('discoverLauncherInstances finds forge 1.20.1 Prism instances from launcher roots', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'prism-root-'))

  try {
    const instanceDir = path.join(root, 'instances', 'EpicFightPack')
    const gameDir = path.join(instanceDir, '.minecraft')
    await mkdir(path.join(gameDir, 'mods'), { recursive: true })
    await writeFile(path.join(instanceDir, 'instance.cfg'), 'name=Epic Fight Pack\n')
    await writeFile(path.join(instanceDir, 'mmc-pack.json'), JSON.stringify({
      components: [
        { uid: 'net.minecraft', version: '1.20.1' },
        { uid: 'net.minecraftforge', version: '47.2.0' }
      ]
    }))

    const instances = await discoverLauncherInstances({
      roots: [
        { launcherType: 'prism', rootDir: root }
      ],
      targetVersion: '1.20.1',
      targetLoader: 'forge'
    })

    assert.equal(instances.length, 1)
    assert.equal(instances[0].name, 'Epic Fight Pack')
    assert.equal(instances[0].instanceId, 'EpicFightPack')
    assert.equal(instances[0].loader, 'forge')
    assert.equal(instances[0].version, '1.20.1')
    assert.equal(instances[0].modsDir, path.join(gameDir, 'mods'))
    assert.equal(instances[0].launchable, true)
  } finally {
    await rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }
})

test('selectPreferredLauncherInstance prefers launchable instances', () => {
  const selected = selectPreferredLauncherInstance([
    normalizeLauncherInstance({
      name: 'Fabric Pack',
      gameDir: 'F:/Instances/FabricPack/.minecraft',
      version: '1.20.1',
      loader: 'fabric',
      launcherType: 'prism'
    }),
    normalizeLauncherInstance({
      name: 'Forge Pack',
      gameDir: 'F:/Instances/ForgePack/.minecraft',
      version: '1.20.1',
      loader: 'forge',
      launcherType: 'prism'
    })
  ])

  assert.equal(selected?.name, 'Forge Pack')
})

test('getDefaultLauncherRoots returns common windows launcher directories', () => {
  const roots = getDefaultLauncherRoots({
    APPDATA: 'C:/Users/Lenovo/AppData/Roaming',
    LOCALAPPDATA: 'C:/Users/Lenovo/AppData/Local'
  })

  assert.equal(roots.some((root) => root.launcherType === 'prism' && root.rootDir.endsWith('PrismLauncher')), true)
  assert.equal(roots.some((root) => root.launcherType === 'hmcl'), true)
  assert.equal(roots.some((root) => root.launcherType === 'pcl'), true)
})
