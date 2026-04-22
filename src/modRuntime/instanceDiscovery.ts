import { access, readdir, readFile } from 'node:fs/promises'
import path from 'node:path'

import type { LauncherInstance, LauncherInstanceInput, LauncherRootCandidate } from './modTypes.js'

function slugify(value = '') {
    return value
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
}

function normalizeCandidateRoots(roots: LauncherRootCandidate[]) {
    const seen = new Set<string>()
    return roots.filter((root) => {
        const key = `${root.launcherType}:${root.rootDir}`.toLowerCase()
        if (seen.has(key)) return false
        seen.add(key)
        return true
    })
}

export function getDefaultLauncherRoots(env: NodeJS.ProcessEnv = process.env) {
    const appData = env.APPDATA || ''
    const localAppData = env.LOCALAPPDATA || ''
    const roots: LauncherRootCandidate[] = []

    if (appData) {
        roots.push(
            { launcherType: 'prism', rootDir: path.join(appData, 'PrismLauncher') },
            { launcherType: 'hmcl', rootDir: path.join(appData, 'HMCL') },
            { launcherType: 'hmcl', rootDir: path.join(appData, '.hmcl') },
            { launcherType: 'pcl', rootDir: path.join(appData, 'PCL') },
            { launcherType: 'pcl', rootDir: path.join(appData, 'PCL2') }
        )
    }

    if (localAppData) {
        roots.push(
            { launcherType: 'pcl', rootDir: path.join(localAppData, 'PCL') },
            { launcherType: 'pcl', rootDir: path.join(localAppData, 'PCL2') }
        )
    }

    return normalizeCandidateRoots(roots)
}

export function normalizeLauncherInstance(input: LauncherInstanceInput): LauncherInstance {
    const version = String(input.version || '')
    const loader = String(input.loader || 'unknown').toLowerCase()
    const launcherType = String(input.launcherType || 'launcher').toLowerCase()
    const id = input.id || slugify(`${launcherType}-${input.name}`)
    const launchable = version === '1.20.1' && loader === 'forge' && !!input.gameDir
    const gameDir = input.gameDir
    const instanceDir = input.instanceDir || gameDir
    const modsDir = input.modsDir || path.join(gameDir, 'mods')
    const instanceId = input.instanceId || path.basename(instanceDir)

    return {
        id,
        name: input.name,
        instanceId,
        instanceDir,
        gameDir,
        modsDir,
        version,
        loader,
        launcherType,
        javaPath: input.javaPath || '',
        launchable
    }
}

async function exists(filePath: string) {
    try {
        await access(filePath)
        return true
    } catch {
        return false
    }
}

function parseSimpleConfig(text = '') {
    const result: Record<string, string> = {}
    for (const line of text.split(/\r?\n/)) {
        const trimmed = line.trim()
        if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith(';')) continue
        const equals = trimmed.indexOf('=')
        if (equals <= 0) continue
        const key = trimmed.slice(0, equals).trim()
        const value = trimmed.slice(equals + 1).trim()
        result[key] = value
    }
    return result
}

function parsePrismPack(pack: any) {
    const components = Array.isArray(pack?.components) ? pack.components : []
    let version = ''
    let loader = 'unknown'

    for (const component of components) {
        const uid = String(component?.uid || '').toLowerCase()
        if (uid === 'net.minecraft') {
            version = String(component?.version || '')
        } else if (uid === 'net.minecraftforge' || uid.includes('forge')) {
            loader = 'forge'
        } else if (uid.includes('fabric')) {
            loader = 'fabric'
        } else if (uid.includes('quilt')) {
            loader = 'quilt'
        }
    }

    return { version, loader }
}

async function discoverPrismInstances(rootDir: string, targetVersion: string, targetLoader: string) {
    const instancesDir = path.join(rootDir, 'instances')
    if (!(await exists(instancesDir))) {
        return []
    }

    const entries = await readdir(instancesDir, { withFileTypes: true })
    const instances: LauncherInstance[] = []

    for (const entry of entries) {
        if (!entry.isDirectory()) continue

        const instanceDir = path.join(instancesDir, entry.name)
        const cfgPath = path.join(instanceDir, 'instance.cfg')
        const packPath = path.join(instanceDir, 'mmc-pack.json')
        const gameDir = path.join(instanceDir, '.minecraft')
        const modsDir = path.join(gameDir, 'mods')

        if (!(await exists(cfgPath)) || !(await exists(packPath)) || !(await exists(modsDir))) {
            continue
        }

        const config = parseSimpleConfig(await readFile(cfgPath, 'utf8'))
        const pack = JSON.parse(await readFile(packPath, 'utf8'))
        const parsed = parsePrismPack(pack)

        const instance = normalizeLauncherInstance({
            name: config.name || entry.name,
            instanceId: entry.name,
            instanceDir,
            gameDir,
            modsDir,
            version: parsed.version,
            loader: parsed.loader,
            launcherType: 'prism'
        })

        if (instance.version === targetVersion && instance.loader === targetLoader) {
            instances.push(instance)
        }
    }

    return instances.sort((left, right) => left.name.localeCompare(right.name))
}

export async function discoverLauncherInstances(options: {
    roots?: LauncherRootCandidate[]
    targetVersion?: string
    targetLoader?: string
} = {}) {
    const roots = normalizeCandidateRoots(options.roots || [])
    const targetVersion = options.targetVersion || '1.20.1'
    const targetLoader = (options.targetLoader || 'forge').toLowerCase()
    const instances: LauncherInstance[] = []

    for (const root of roots) {
        const launcherType = String(root.launcherType || '').toLowerCase()
        if (launcherType === 'prism') {
            instances.push(...await discoverPrismInstances(root.rootDir, targetVersion, targetLoader))
        }
    }

    return instances.sort((left, right) => left.name.localeCompare(right.name))
}

export function selectPreferredLauncherInstance(instances: LauncherInstance[] = []) {
    return instances.find((instance) => instance.launchable) || instances[0] || null
}
