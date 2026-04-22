import type { LauncherInstance, ModScanResult } from './modTypes.js'

export function applyModScanResult(settings: any, modsDir: string, result: ModScanResult) {
    if (!settings.mod_runtime) {
        settings.mod_runtime = {}
    }

    settings.mod_runtime.scan_dir = modsDir
    settings.mod_runtime.detected_mods = result.mods
    settings.mod_runtime.detected_capabilities = result.capabilities

    return settings
}

export function applySelectedInstance(settings: any, instance: LauncherInstance) {
    if (!settings.mod_runtime) {
        settings.mod_runtime = {}
    }

    settings.mod_runtime.selected_instance = instance
    return settings
}
