// @ts-nocheck
import path from 'node:path';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { resolveModRuntimeTarget } from '../src/modRuntime/bootstrap.js';
import { getDefaultLauncherRoots } from '../src/modRuntime/instanceDiscovery.js';
import { deployForgeAgentJar } from '../src/forgeAgent/deploySupport.js';

const argv = await yargs(hideBin(process.argv))
    .option('mods_dir', {
        type: 'string',
        describe: 'Explicit mods directory to deploy the forge-agent jar into'
    })
    .option('instance_dir', {
        type: 'string',
        describe: 'Game or instance directory to resolve a mods directory from'
    })
    .option('launcher_root', {
        type: 'array',
        describe: 'Launcher roots to scan for a managed instance'
    })
    .option('jar_name', {
        type: 'string',
        describe: 'Optional forge-agent jar name override'
    })
    .help()
    .parse();

const workspaceRoot = path.resolve(path.join(import.meta.dirname, '..'));
const runtimeTarget = await resolveModRuntimeTarget({
    modsDir: argv.mods_dir,
    instanceDir: argv.instance_dir,
    launcherRoots: (argv.launcher_root?.length
        ? (argv.launcher_root || []).map((root) => ({
            launcherType: 'prism',
            rootDir: String(root)
        }))
        : getDefaultLauncherRoots()),
    targetVersion: '1.20.1',
    targetLoader: 'forge'
});

if (!runtimeTarget?.modsDir) {
    throw new Error('Could not resolve a target mods directory for forge-agent deployment')
}

const deployed = await deployForgeAgentJar({
    workspaceRoot,
    modsDir: runtimeTarget.modsDir,
    jarName: argv.jar_name
});

console.log(`[forge-agent] deployed ${deployed.sourcePath} -> ${deployed.targetPath}`);
