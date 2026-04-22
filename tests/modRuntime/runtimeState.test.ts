import test from 'node:test'
import assert from 'node:assert/strict'

import { applyModScanResult, applySelectedInstance } from '../../src/modRuntime/runtimeState.js'

test('applyModScanResult stores discovered mods and capability tags on settings', () => {
  const settings = {
    mod_runtime: {
      enabled: true
    }
  }

  applyModScanResult(settings, 'F:/Instances/EpicPack/.minecraft/mods', {
    mods: [
      {
        modId: 'epicfight',
        displayName: 'Epic Fight',
        sourceJar: 'epicfight.jar'
      }
    ],
    capabilities: {
      hasEpicFight: true,
      hasSlashBlade: false,
      tags: ['combat.epic_fight']
    }
  })

  assert.equal(settings.mod_runtime.scan_dir, 'F:/Instances/EpicPack/.minecraft/mods')
  assert.equal(settings.mod_runtime.detected_capabilities.hasEpicFight, true)
  assert.equal(settings.mod_runtime.detected_mods[0].modId, 'epicfight')
})

test('applySelectedInstance stores selected launcher instance metadata on settings', () => {
  const settings = {
    mod_runtime: {
      enabled: true
    }
  }

  applySelectedInstance(settings, {
    id: 'prism-epic-fight-pack',
    name: 'Epic Fight Pack',
    instanceId: 'EpicFightPack',
    gameDir: 'F:/Instances/EpicFightPack/.minecraft',
    instanceDir: 'F:/Instances/EpicFightPack',
    modsDir: 'F:/Instances/EpicFightPack/.minecraft/mods',
    version: '1.20.1',
    loader: 'forge',
    launcherType: 'prism',
    javaPath: '',
    launchable: true
  })

  assert.equal(settings.mod_runtime.selected_instance.name, 'Epic Fight Pack')
  assert.equal(settings.mod_runtime.selected_instance.instanceId, 'EpicFightPack')
  assert.equal(settings.mod_runtime.selected_instance.modsDir, 'F:/Instances/EpicFightPack/.minecraft/mods')
})
