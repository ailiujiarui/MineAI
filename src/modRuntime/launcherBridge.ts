import { spawn } from 'node:child_process'

export interface PrismLaunchCommandOptions {
    executablePath: string
    rootDir: string
    instanceId: string
    server?: string
    profile?: string
}

export interface LaunchCommand {
    command: string
    args: string[]
}

export function buildPrismLaunchCommand(options: PrismLaunchCommandOptions): LaunchCommand {
    const args = [
        '--dir',
        options.rootDir,
        '--launch',
        options.instanceId
    ]

    if (options.server) {
        args.push('--server', options.server)
    }

    if (options.profile) {
        args.push('--profile', options.profile)
    }

    return {
        command: options.executablePath,
        args
    }
}

export async function launchPrismInstance(
    options: PrismLaunchCommandOptions,
    deps: {
        spawnImpl?: typeof spawn
    } = {}
) {
    const spawnImpl = deps.spawnImpl || spawn
    const command = buildPrismLaunchCommand(options)

    return spawnImpl(command.command, command.args, {
        stdio: 'inherit',
        shell: false,
        windowsHide: false
    })
}
