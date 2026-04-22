// @ts-nocheck
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { listBridgeClients } from '../src/clientBridge/bridgeControlClient.js';
import { formatBridgeClientSummary } from '../src/clientBridge/bridgeStatus.js';

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
    .option('summary', {
        type: 'boolean',
        default: false,
        describe: 'Print a compact summary instead of raw JSON'
    })
    .help()
    .parse();

const clients = await listBridgeClients({ host: argv.host, port: argv.port });
if (argv.summary) {
    if (clients.length === 0) {
        console.log('No connected clients.');
    } else {
        for (const client of clients) {
            console.log(formatBridgeClientSummary(client));
        }
    }
} else {
    console.log(JSON.stringify(clients, null, 2));
}
