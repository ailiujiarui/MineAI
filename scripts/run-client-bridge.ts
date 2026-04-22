// @ts-nocheck
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { createClientBridgeRuntime } from '../src/clientBridge/bridgeRuntime.js';

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
    .help()
    .parse();

const runtime = createClientBridgeRuntime();
const address = await runtime.start({
    host: argv.host,
    port: argv.port
});

console.log(`[client-bridge] listening on ${address.address}:${address.port}`);

process.on('SIGINT', async () => {
    await runtime.stop();
    process.exit(0);
});

process.on('SIGTERM', async () => {
    await runtime.stop();
    process.exit(0);
});
