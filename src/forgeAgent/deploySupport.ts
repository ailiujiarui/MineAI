import { copyFile, mkdir } from 'node:fs/promises'
import path from 'node:path'

export const DEFAULT_FORGE_AGENT_JAR_NAME = 'gameai_forge_agent-0.1.0.jar'

export function resolveForgeAgentJarPath(workspaceRoot: string, jarName = DEFAULT_FORGE_AGENT_JAR_NAME) {
    return path.join(workspaceRoot, 'forge-agent', 'build', 'libs', jarName)
}

export async function deployForgeAgentJar(options: {
    workspaceRoot: string
    modsDir: string
    jarName?: string
}) {
    const jarName = options.jarName || DEFAULT_FORGE_AGENT_JAR_NAME
    const sourcePath = resolveForgeAgentJarPath(options.workspaceRoot, jarName)
    const targetPath = path.join(options.modsDir, jarName)

    await mkdir(options.modsDir, { recursive: true })
    await copyFile(sourcePath, targetPath)

    return {
        sourcePath,
        targetPath
    }
}
