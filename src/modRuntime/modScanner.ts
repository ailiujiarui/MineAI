import { readdir } from 'node:fs/promises'
import path from 'node:path'

import { deriveModCapabilities } from './modKnowledge.js'
import { parseModJarMetadata } from './modMetadata.js'
import type { DiscoveredMod, ModScanResult } from './modTypes.js'

function isModArchive(fileName: string) {
    const ext = path.extname(fileName).toLowerCase()
    return ext === '.jar' || ext === '.litemod'
}

export async function scanModDirectory(modsDir: string): Promise<ModScanResult> {
    const entries = await readdir(modsDir, { withFileTypes: true })
    const jars = entries
        .filter((entry) => entry.isFile() && isModArchive(entry.name))
        .map((entry) => path.join(modsDir, entry.name))
        .sort()

    const mods: DiscoveredMod[] = []
    for (const jar of jars) {
        const parsed = await parseModJarMetadata(jar)
        mods.push(...parsed)
    }

    mods.sort((left, right) => {
        const byId = left.modId.localeCompare(right.modId)
        if (byId !== 0) return byId
        return left.sourceJar.localeCompare(right.sourceJar)
    })

    return {
        mods,
        capabilities: deriveModCapabilities(mods)
    }
}
