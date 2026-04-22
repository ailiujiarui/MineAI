// @ts-nocheck
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { createClientBridgeRuntime } from '../src/clientBridge/bridgeRuntime.js';
import { createBridgeCombatRuntime } from '../src/clientBridge/bridgeCombatRuntime.js';

const argv = await yargs(hideBin(process.argv))
    .option('host', {
        type: 'string',
        default: '127.0.0.1',
        describe: 'Bridge host'
    })
    .option('port', {
        type: 'number',
        default: 18765,
        describe: 'Bridge port'
    })
    .option('tick_ms', {
        type: 'number',
        default: 150,
        describe: 'Combat loop interval in milliseconds'
    })
    .option('attack_range', {
        type: 'number',
        default: 3.1,
        describe: 'Attack range threshold for auto combat'
    })
    .help()
    .parse();

const bridgeRuntime = createClientBridgeRuntime();
const address = await bridgeRuntime.start({
    host: argv.host,
    port: argv.port
});

console.log(`[client-autocombat] bridge listening on ${address.address}:${address.port}`);

const combatRuntime = createBridgeCombatRuntime({
    bridgeServer: bridgeRuntime.getServer(),
    attackRange: argv.attack_range
});

const interval = setInterval(() => {
    combatRuntime.tick().catch((error) => {
        console.error('[client-autocombat] combat tick failed:', error);
    });
}, argv.tick_ms);

async function shutdown() {
    clearInterval(interval);
    await bridgeRuntime.stop();
    process.exit(0);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
