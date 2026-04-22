import {
    type KnowledgeSearchKind,
    type KnowledgeSearchResult,
    type ModKnowledgeInput,
    type ModRecord,
    type OverrideSource,
    type RegistryEntry,
    type RegistryKind
} from './modKnowledgeTypes.js';

function toSearchableText(parts: Array<string | undefined>) {
    return parts.filter(Boolean).join(' ').toLowerCase();
}

function toRegistryResults(kind: RegistryKind, entries: RegistryEntry[]): KnowledgeSearchResult[] {
    return entries.map((entry) => ({
        kind,
        id: entry.id,
        name: entry.name,
        sourceMod: entry.sourceMod,
        description: entry.notes?.join(' ')
    }));
}

export function createModKnowledgeIndex(input: ModKnowledgeInput) {
    const modsById = new Map<string, ModRecord>();
    for (const mod of input.mods) {
        modsById.set(mod.id, mod);
    }

    const modResults: KnowledgeSearchResult[] = input.mods.map((mod) => ({
        kind: 'mod',
        id: mod.id,
        name: mod.name,
        description: mod.description
    }));

    const overrideResults: KnowledgeSearchResult[] = input.overrides.map((source) => ({
        kind: 'override',
        id: source.id,
        name: source.description || source.id,
        description: source.kind,
        path: source.path
    }));

    const registries = {
        item: toRegistryResults('item', input.registries.items),
        block: toRegistryResults('block', input.registries.blocks),
        entity: toRegistryResults('entity', input.registries.entities),
        recipe: toRegistryResults('recipe', input.registries.recipes)
    };

    const allResults = [
        ...modResults,
        ...overrideResults,
        ...registries.item,
        ...registries.block,
        ...registries.entity,
        ...registries.recipe
    ];

    function search(query: string, kind?: KnowledgeSearchKind) {
        const normalized = query.trim().toLowerCase();
        if (!normalized) {
            return [];
        }

        return allResults.filter((result) => {
            if (kind && result.kind !== kind) {
                return false;
            }

            return toSearchableText([
                result.id,
                result.name,
                result.sourceMod,
                result.description,
                result.path
            ]).includes(normalized);
        });
    }

    function findMod(id: string) {
        return modsById.get(id) || null;
    }

    function listOverrideSources(): OverrideSource[] {
        return [...input.overrides];
    }

    return {
        search,
        findMod,
        listOverrideSources
    };
}
