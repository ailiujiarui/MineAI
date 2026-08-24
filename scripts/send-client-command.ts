// @ts-nocheck
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { randomUUID } from 'node:crypto';
import { requestBridgeStopAll } from '../src/clientBridge/bridgeControlClient.js';

const argv = await yargs(hideBin(process.argv))
    .option('client', {
        type: 'string',
        demandOption: true,
        describe: 'Target connected client id'
    })
    .option('action', {
        type: 'string',
        choices: ['stop_all'],
        demandOption: true,
        describe: 'Structured action to send'
    })
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
    .option('yaw', {
        type: 'number',
        default: 0,
        describe: 'Yaw for look commands'
    })
    .option('pitch', {
        type: 'number',
        default: 0,
        describe: 'Pitch for look commands'
    })
    .option('target', {
        type: 'number',
        describe: 'Target entity id for attack commands'
    })
    .option('forward', {
        type: 'number',
        default: 0,
        describe: 'Forward movement amount for move commands'
    })
    .option('strafe', {
        type: 'number',
        default: 0,
        describe: 'Strafe movement amount for move commands'
    })
    .option('jump', {
        type: 'boolean',
        default: false,
        describe: 'Whether move commands should jump'
    })
    .option('sprint', {
        type: 'boolean',
        default: false,
        describe: 'Whether move commands should sprint'
    })
    .option('slot', {
        type: 'number',
        describe: 'Hotbar slot index for equip_hotbar commands'
    })
    .option('skill_slot', {
        type: 'string',
        default: 'weapon_innate',
        describe: 'Skill slot for use_skill commands'
    })
    .help()
    .parse();

const result = await requestBridgeStopAll({
    host: argv.host,
    port: argv.port,
    requestId: randomUUID(),
    clientId: argv.client
});
console.log(`[client-bridge] ${argv.action} result for ${argv.client}: ${JSON.stringify(result)}`);
