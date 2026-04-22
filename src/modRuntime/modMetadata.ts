import path from 'node:path'
import { readFile } from 'node:fs/promises'
import { readForgeModJson, readForgeModToml } from '@xmcl/mod-parser'

import type { DiscoveredMod, ModLoaderHint } from './modTypes.js'

function unique<T>(items: T[]) {
    return [...new Set(items)]
}

function cleanString(value: unknown) {
    return typeof value === 'string' ? value.trim() : ''
}

export function inferModIdFromJarName(jarPath: string) {
    const base = path.basename(jarPath, path.extname(jarPath)).toLowerCase()
    const noVersion = base
        .replace(/[-_]?forge[-_]?.*$/i, '')
        .replace(/[-_]?fabric[-_]?.*$/i, '')
        .replace(/[-_]?quilt[-_]?.*$/i, '')
        .replace(/[-_]?mc[\d.\-+_]*$/i, '')
        .replace(/[-_]?v?\d[\w.\-+_]*$/i, '')
    const normalized = noVersion
        .replace(/[^a-z0-9]+/g, '_')
        .replace(/^_+|_+$/g, '')

    return normalized || base.replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')
}

function toDiscoveredMod(input: {
    modId?: string
    displayName?: string
    version?: string
    description?: string
    loaderHints?: ModLoaderHint[]
    sourceJar: string
    confidence?: 'metadata' | 'filename'
}): DiscoveredMod {
    const fallbackModId = inferModIdFromJarName(input.sourceJar)
    const modId = cleanString(input.modId) || fallbackModId
    const displayName = cleanString(input.displayName) || modId

    return {
        modId,
        displayName,
        version: cleanString(input.version) || undefined,
        description: cleanString(input.description) || undefined,
        loaderHints: unique((input.loaderHints || ['unknown']).filter(Boolean)),
        sourceJar: path.basename(input.sourceJar),
        confidence: input.confidence || (cleanString(input.modId) ? 'metadata' : 'filename')
    }
}

export async function parseModJarMetadata(jarPath: string): Promise<DiscoveredMod[]> {
    const discovered: DiscoveredMod[] = []
    const jarBytes = await readFile(jarPath)

    try {
        const tomlMods = await readForgeModToml(jarBytes)
        for (const mod of tomlMods) {
            discovered.push(toDiscoveredMod({
                modId: mod.modid,
                displayName: mod.displayName,
                version: mod.version,
                description: mod.description,
                loaderHints: ['forge'],
                sourceJar: jarPath,
                confidence: 'metadata'
            }))
        }
    } catch {
        // Fallback below.
    }

    if (discovered.length === 0) {
        try {
            const jsonMods = await readForgeModJson(jarBytes)
            for (const mod of jsonMods) {
                discovered.push(toDiscoveredMod({
                    modId: mod.modid,
                    displayName: mod.name,
                    version: mod.version,
                    description: mod.description,
                    loaderHints: ['forge'],
                    sourceJar: jarPath,
                    confidence: 'metadata'
                }))
            }
        } catch {
            // Fallback below.
        }
    }

    if (discovered.length === 0) {
        discovered.push(toDiscoveredMod({
            sourceJar: jarPath,
            confidence: 'filename'
        }))
    }

    return discovered
}
