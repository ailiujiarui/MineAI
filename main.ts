// @ts-nocheck
import * as Mindcraft from './src/mindcraft/mindcraft.js';
import settings from './settings.js';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { readFileSync } from 'fs';
import { initRunContext } from './src/utils/runContext.js';
import { createClientBridgeRuntime } from './src/clientBridge/bridgeRuntime.js';
import { deployForgeAgentJar } from './src/forgeAgent/deploySupport.js';
import { resolveModRuntimeTarget } from './src/modRuntime/bootstrap.js';
import { getDefaultLauncherRoots } from './src/modRuntime/instanceDiscovery.js';
import { launchPrismInstance } from './src/modRuntime/launcherBridge.js';
import { scanModDirectory } from './src/modRuntime/modScanner.js';
import { applyModScanResult, applySelectedInstance } from './src/modRuntime/runtimeState.js';

function parseArguments() {
    return yargs(hideBin(process.argv))
        .option('profiles', {
            type: 'array',
            describe: 'List of agent profile paths',
        })
        .option('start_client_bridge', {
            type: 'boolean',
            default: false,
            describe: 'Start the localhost client bridge server in this process'
        })
        .option('client_bridge_host', {
            type: 'string',
            default: '127.0.0.1',
            describe: 'Host for the localhost client bridge server'
        })
        .option('client_bridge_port', {
            type: 'number',
            default: 18765,
            describe: 'Port for the localhost client bridge server'
        })
        .option('launch_client', {
            type: 'boolean',
            default: false,
            describe: 'Launch the selected client instance through a supported launcher bridge'
        })
        .option('deploy_forge_agent', {
            type: 'boolean',
            default: false,
            describe: 'Deploy the built forge-agent jar into the resolved mods directory before launching'
        })
        .option('launcher_exe', {
            type: 'string',
            describe: 'Path to the launcher executable when launching a managed instance'
        })
        .option('join_server', {
            type: 'string',
            describe: 'Optional server address to join when launching a client instance'
        })
        .option('launcher_profile', {
            type: 'string',
            describe: 'Optional launcher account/profile name to use for launch'
        })
        .option('instance_dir', {
            type: 'string',
            describe: 'Optional game or instance directory to resolve a modded runtime from'
        })
        .option('launcher_root', {
            type: 'array',
            describe: 'Optional launcher root directories to scan for managed instances'
        })
        .option('mods_dir', {
            type: 'string',
            describe: 'Optional mod directory to scan for runtime capability tags'
        })
        .option('task_path', {
            type: 'string',
            describe: 'Path to task file to execute'
        })
        .option('task_id', {
            type: 'string',
            describe: 'Task ID to execute'
        })
        .help()
        .alias('help', 'h')
        .parse();
}
const args = parseArguments();
if (args.profiles) {
    settings.profiles = args.profiles;
}
if (args.task_path) {
    let tasks = JSON.parse(readFileSync(args.task_path, 'utf8'));
    if (args.task_id) {
        settings.task = tasks[args.task_id];
        settings.task.task_id = args.task_id;
    }
    else {
        throw new Error('task_id is required when task_path is provided');
    }
}

// these environment variables override certain settings
if (process.env.MINECRAFT_PORT) {
    settings.port = process.env.MINECRAFT_PORT;
}
if (process.env.MINDSERVER_PORT) {
    settings.mindserver_port = process.env.MINDSERVER_PORT;
}
if (process.env.PROFILES && JSON.parse(process.env.PROFILES).length > 0) {
    settings.profiles = JSON.parse(process.env.PROFILES);
}
if (process.env.INSECURE_CODING) {
    settings.allow_insecure_coding = true;
}
if (process.env.BLOCKED_ACTIONS) {
    settings.blocked_actions = JSON.parse(process.env.BLOCKED_ACTIONS);
}
if (process.env.MAX_MESSAGES) {
    settings.max_messages = process.env.MAX_MESSAGES;
}
if (process.env.NUM_EXAMPLES) {
    settings.num_examples = process.env.NUM_EXAMPLES;
}
if (process.env.LOG_ALL) {
    settings.log_all_prompts = process.env.LOG_ALL;
}
if (process.env.SETTINGS_JSON) {
    try {
        Object.assign(settings, JSON.parse(process.env.SETTINGS_JSON));
    } catch (err) {
        console.error("Failed to parse environment variable for SETTINGS_JSON:", err);
    }
}

let clientBridgeRuntime = null;
if (args.start_client_bridge) {
    clientBridgeRuntime = createClientBridgeRuntime();
    const bridgeAddress = await clientBridgeRuntime.start({
        host: String(args.client_bridge_host),
        port: Number(args.client_bridge_port)
    });
    console.log(`[client-bridge] listening on ${bridgeAddress.address}:${bridgeAddress.port}`);
}

const runtimeTarget = settings.mod_runtime?.enabled === false
    ? null
    : await resolveModRuntimeTarget({
        modsDir: args.mods_dir || process.env.MODS_DIR,
        instanceDir: args.instance_dir || process.env.INSTANCE_DIR,
        launcherRoots: (args.launcher_root?.length
            ? (args.launcher_root || []).map((root) => ({
                launcherType: 'prism',
                rootDir: String(root)
            }))
            : getDefaultLauncherRoots()),
        targetVersion: settings.mod_runtime?.target_minecraft_version,
        targetLoader: settings.mod_runtime?.target_loader
    });

if (runtimeTarget?.selectedInstance) {
    applySelectedInstance(settings, runtimeTarget.selectedInstance);
    console.log(`[mod-runtime] selected instance: ${runtimeTarget.selectedInstance.name}`);
}

if (args.deploy_forge_agent && runtimeTarget?.modsDir) {
    try {
        const deployed = await deployForgeAgentJar({
            workspaceRoot: process.cwd(),
            modsDir: runtimeTarget.modsDir
        });
        console.log(`[forge-agent] deployed ${deployed.sourcePath} -> ${deployed.targetPath}`);
    } catch (err) {
        console.error('[forge-agent] deployment failed:', err);
    }
}

if (args.launch_client && runtimeTarget?.selectedInstance) {
    const instance = runtimeTarget.selectedInstance;
    if (instance.launcherType === 'prism') {
        if (!args.launcher_exe) {
            console.error('[mod-runtime] --launch_client for Prism instances requires --launcher_exe')
        } else {
            await launchPrismInstance({
                executablePath: String(args.launcher_exe),
                rootDir: instance.instanceDir.includes('\\instances\\') || instance.instanceDir.includes('/instances/')
                    ? instance.instanceDir.split(/[/\\]instances[/\\]/)[0]
                    : pathLikeRoot(instance.instanceDir),
                instanceId: instance.instanceId,
                server: args.join_server ? String(args.join_server) : undefined,
                profile: args.launcher_profile ? String(args.launcher_profile) : undefined
            })
            console.log(`[mod-runtime] requested Prism launch for ${instance.instanceId}`)
        }
    } else {
        console.log(`[mod-runtime] launcher bridge not implemented yet for ${instance.launcherType}`)
    }
}

if (runtimeTarget?.modsDir && settings.mod_runtime?.enabled !== false) {
    try {
        const modScan = await scanModDirectory(runtimeTarget.modsDir);
        applyModScanResult(settings, runtimeTarget.modsDir, modScan);
        console.log(`[mod-runtime] scanned ${modScan.mods.length} mods from ${runtimeTarget.modsDir}`);
        console.log(`[mod-runtime] detected capabilities: ${modScan.capabilities.tags.join(', ') || 'none'}`);
    } catch (err) {
        console.error(`[mod-runtime] failed to scan mods directory ${runtimeTarget.modsDir}:`, err);
    }
}

function pathLikeRoot(instanceDir) {
    const parts = String(instanceDir).split(/[/\\]/)
    if (parts.length > 1) {
        return parts.slice(0, parts.length - 1).join('/')
    }
    return String(instanceDir)
}


Mindcraft.init(false, settings.mindserver_port, settings.auto_open_ui);
initRunContext(settings.run?.base_dir || './runs');

for (let profile of settings.profiles) {
    const profile_json = JSON.parse(readFileSync(profile, 'utf8'));
    settings.profile = profile_json;
    Mindcraft.createAgent(settings);
}
