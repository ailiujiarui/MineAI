import path from 'node:path'

export const FORGE_AGENT_GRADLE_VERSION = '8.1.1'

export function buildGradleDistributionUrl(version = FORGE_AGENT_GRADLE_VERSION) {
    return `https://services.gradle.org/distributions/gradle-${version}-bin.zip`
}

export function buildForgeAgentGradleArgs(workspaceRoot: string, task = 'build') {
    return [
        '-p',
        path.join(workspaceRoot, 'forge-agent').replace(/\\/g, '/'),
        task
    ]
}
