export type ModLoaderHint = 'forge' | 'fabric' | 'quilt' | 'unknown'

export interface DiscoveredMod {
    modId: string
    displayName: string
    version?: string
    loaderHints?: ModLoaderHint[]
    description?: string
    sourceJar: string
    confidence?: 'metadata' | 'filename'
}

export interface ModCapabilities {
    hasEpicFight: boolean
    hasSlashBlade: boolean
    tags: string[]
}

export interface ModScanResult {
    mods: DiscoveredMod[]
    capabilities: ModCapabilities
}

export interface LauncherInstanceInput {
    id?: string
    name: string
    instanceId?: string
    instanceDir?: string
    gameDir: string
    modsDir?: string
    version?: string
    loader?: string
    launcherType?: string
    javaPath?: string
}

export interface LauncherInstance {
    id: string
    name: string
    instanceId: string
    instanceDir: string
    gameDir: string
    modsDir: string
    version: string
    loader: string
    launcherType: string
    javaPath: string
    launchable: boolean
}

export interface LauncherRootCandidate {
    launcherType: string
    rootDir: string
}
