import type { DiscoveredMod, ModCapabilities } from './modTypes.js'

function normalizeModId(value = '') {
    return value.trim().toLowerCase()
}

export function deriveModCapabilities(mods: DiscoveredMod[] = []): ModCapabilities {
    const tags = new Set<string>()
    let hasEpicFight = false
    let hasSlashBlade = false

    for (const mod of mods) {
        const modId = normalizeModId(mod.modId)
        if (modId === 'epicfight' || modId === 'epic_fight') {
            hasEpicFight = true
            tags.add('combat.epic_fight')
        }
        if (modId === 'slashblade' || modId === 'slash_blade') {
            hasSlashBlade = true
            tags.add('weapon.slashblade')
        }
        if (modId === 'touhou_little_maid') {
            tags.add('maid.touhou_little_maid')
        }
    }

    return {
        hasEpicFight,
        hasSlashBlade,
        tags: [...tags].sort()
    }
}
