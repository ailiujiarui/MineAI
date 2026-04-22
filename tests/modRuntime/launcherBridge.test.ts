import test from 'node:test'
import assert from 'node:assert/strict'

import { buildPrismLaunchCommand, launchPrismInstance } from '../../src/modRuntime/launcherBridge.js'

test('buildPrismLaunchCommand uses official Prism CLI flags for instance launch and server join', () => {
  const command = buildPrismLaunchCommand({
    executablePath: 'C:/Launchers/PrismLauncher/prismlauncher.exe',
    rootDir: 'C:/Users/Lenovo/AppData/Roaming/PrismLauncher',
    instanceId: 'EpicFightPack',
    server: 'example.org:25565',
    profile: 'BotAccount'
  })

  assert.equal(command.command, 'C:/Launchers/PrismLauncher/prismlauncher.exe')
  assert.deepEqual(command.args, [
    '--dir',
    'C:/Users/Lenovo/AppData/Roaming/PrismLauncher',
    '--launch',
    'EpicFightPack',
    '--server',
    'example.org:25565',
    '--profile',
    'BotAccount'
  ])
})

test('buildPrismLaunchCommand omits optional flags when server and profile are absent', () => {
  const command = buildPrismLaunchCommand({
    executablePath: 'prismlauncher.exe',
    rootDir: 'C:/Users/Lenovo/AppData/Roaming/PrismLauncher',
    instanceId: 'EpicFightPack'
  })

  assert.deepEqual(command.args, [
    '--dir',
    'C:/Users/Lenovo/AppData/Roaming/PrismLauncher',
    '--launch',
    'EpicFightPack'
  ])
})

test('launchPrismInstance spawns PrismLauncher with the generated command', async () => {
  const calls: any[] = []

  const child = await launchPrismInstance({
    executablePath: 'prismlauncher.exe',
    rootDir: 'C:/Users/Lenovo/AppData/Roaming/PrismLauncher',
    instanceId: 'EpicFightPack',
    server: 'example.org:25565'
  }, {
    spawnImpl(command, args, options) {
      calls.push({ command, args, options })
      return {
        on() {},
      } as any
    }
  })

  assert.equal(child != null, true)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].command, 'prismlauncher.exe')
  assert.deepEqual(calls[0].args, [
    '--dir',
    'C:/Users/Lenovo/AppData/Roaming/PrismLauncher',
    '--launch',
    'EpicFightPack',
    '--server',
    'example.org:25565'
  ])
  assert.equal(calls[0].options.shell, false)
})
