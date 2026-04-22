export type ModLoader = 'forge' | 'fabric' | 'quilt' | 'neoforge' | 'unknown';

export type RegistryKind = 'item' | 'block' | 'entity' | 'recipe';

export type OverrideSourceKind = 'kubejs' | 'crafttweaker' | 'patchouli' | 'datapack' | 'quest' | 'unknown';

export type ModRecord = {
    id: string;
    name: string;
    version?: string;
    loader?: ModLoader;
    description?: string;
};

export type RegistryEntry = {
    id: string;
    name: string;
    sourceMod: string;
    tags?: string[];
    notes?: string[];
};

export type RegistrySummary = {
    items: RegistryEntry[];
    blocks: RegistryEntry[];
    entities: RegistryEntry[];
    recipes: RegistryEntry[];
};

export type OverrideSource = {
    id: string;
    kind: OverrideSourceKind;
    path: string;
    description?: string;
};

export type ModKnowledgeInput = {
    mods: ModRecord[];
    registries: RegistrySummary;
    overrides: OverrideSource[];
};

export type KnowledgeSearchKind = RegistryKind | 'mod' | 'override';

export type KnowledgeSearchResult = {
    kind: KnowledgeSearchKind;
    id: string;
    name: string;
    sourceMod?: string;
    description?: string;
    path?: string;
};
