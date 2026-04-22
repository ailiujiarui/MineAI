import { access } from 'node:fs/promises'
import path from 'node:path'

import { discoverLauncherInstances, normalizeLauncherInstance, selectPreferredLauncherInstance } from './instanceDiscovery.js'
import type { LauncherRootCandidate } from './modTypes.js'

async function exists(filePath: string) {
    try {
        await access(filePath)
        return true
    } catch {
        return false
    }
}

async function resolveModsDirFromInstanceInput(instanceDir: string) {
    const directModsDir = path.join(instanceDir, 'mods')
    if (await exists(directModsDir)) {
        return {
            instanceDir,
            gameDir: instanceDir,
            modsDir: directModsDir
        }
    }

    const nestedGameDir = path.join(instanceDir, '.minecraft')
    const nestedModsDir = path.join(nestedGameDir, 'mods')
    if (await exists(nestedModsDir)) {
        return {
            instanceDir,
            gameDir: nestedGameDir,
            modsDir: nestedModsDir
        }
    }

    return null
}

export async function resolveModRuntimeTarget(options: {
    modsDir?: string
    instanceDir?: string
    launcherRoots?: LauncherRootCandidate[]
    targetVersion?: string
    targetLoader?: string
}) {
    const targetVersion = options.targetVersion || '1.20.1'
    const targetLoader = options.targetLoader || 'forge'

    if (options.modsDir) {
        return {
            modsDir: options.modsDir,
            selectedInstance: null
        }
    }

    if (options.instanceDir) {
        const resolved = await resolveModsDirFromInstanceInput(options.instanceDir)
        if (resolved) {
            return {
                modsDir: resolved.modsDir,
                selectedInstance: normalizeLauncherInstance({
                    name: path.basename(resolved.instanceDir),
                    instanceDir: resolved.instanceDir,
                    gameDir: resolved.gameDir,
                    modsDir: resolved.modsDir,
                    version: targetVersion,
                    loader: targetLoader,
                    launcherType: 'manual'
                })
            }
        }
    }

    if (options.launcherRoots?.length) {
        const instances = await discoverLauncherInstances({
            roots: options.launcherRoots,
            targetVersion,
            targetLoader
        })
        const selectedInstance = selectPreferredLauncherInstance(instances)
        if (selectedInstance) {
            return {
                modsDir: selectedInstance.modsDir,
                selectedInstance
            }
        }
    }

    return null
}
