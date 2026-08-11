import yargs from 'yargs'
import { hideBin } from 'yargs/helpers'

import { runForgeRuntimeSmoke } from '../src/forgeAgent/runtimeSmoke.js'

const argv = await yargs(hideBin(process.argv))
    .option('instance_dir', {
        type: 'string',
        demandOption: true,
        describe: 'Minecraft game directory containing the mods folder'
    })
    .option('jar_name', {
        type: 'string',
        describe: 'Forge Agent jar filename inside the mods folder'
    })
    .option('host', {
        type: 'string',
        default: '127.0.0.1',
        describe: 'Bridge listen host'
    })
    .option('port', {
        type: 'number',
        default: 18765,
        describe: 'Bridge listen port'
    })
    .option('client', {
        type: 'string',
        describe: 'Expected Forge client id; required when multiple clients connect'
    })
    .option('minecraft_version', {
        type: 'string',
        default: '1.20.1',
        describe: 'Expected Minecraft version from the Forge hello message'
    })
    .option('timeout_ms', {
        type: 'number',
        default: 120_000,
        describe: 'Maximum time to wait for hello and a fresh snapshot'
    })
    .option('snapshot_fresh_ms', {
        type: 'number',
        default: 2_000,
        describe: 'Maximum accepted snapshot age'
    })
    .option('ack_timeout_ms', {
        type: 'number',
        default: 5_000,
        describe: 'Maximum time to wait for a command acknowledgement'
    })
    .option('command', {
        type: 'string',
        choices: ['none', 'stop_all'] as const,
        default: 'none' as const,
        describe: 'Optional low-risk command to verify acknowledgement handling'
    })
    .strict()
    .help()
    .parse()

const abortController = new AbortController()
const abort = () => abortController.abort(new Error('Interrupted while waiting for the Forge client'))
process.once('SIGINT', abort)
process.once('SIGTERM', abort)

try {
    const result = await runForgeRuntimeSmoke({
        instanceDir: argv.instance_dir,
        jarName: argv.jar_name,
        host: argv.host,
        port: argv.port,
        clientId: argv.client,
        expectedMinecraftVersion: argv.minecraft_version,
        connectTimeoutMs: argv.timeout_ms,
        snapshotFreshnessMs: argv.snapshot_fresh_ms,
        ackTimeoutMs: argv.ack_timeout_ms,
        command: argv.command,
        signal: abortController.signal,
        onListening(address) {
            console.log(`[forge-smoke] bridge listening on ${address.address}:${address.port}`)
            console.log('[forge-smoke] launch the Forge instance and join a world; waiting for hello and snapshot...')
        }
    })
    console.log(JSON.stringify(result, null, 2))
} catch (error) {
    console.error(`[forge-smoke] ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
} finally {
    process.removeListener('SIGINT', abort)
    process.removeListener('SIGTERM', abort)
}
