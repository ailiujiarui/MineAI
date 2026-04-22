// @ts-nocheck
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import {
    createAttackCommand,
    createEquipHotbarCommand,
    createLookCommand,
    createMoveCommand,
    createStopAllCommand
    ,createUseSkillCommand
} from '../src/clientBridge/clientCommander.js';
import { sendBridgeCommand } from '../src/clientBridge/bridgeControlClient.js';

const argv = await yargs(hideBin(process.argv))
    .option('client', {
        type: 'string',
        demandOption: true,
        describe: 'Target connected client id'
    })
    .option('action', {
        type: 'string',
        choices: ['look', 'attack', 'stop_all', 'move', 'equip_hotbar', 'use_skill'],
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

let command;

if (argv.action === 'look') {
    command = createLookCommand(`look-${Date.now()}`, argv.yaw, argv.pitch);
} else if (argv.action === 'attack') {
    command = createAttackCommand(`attack-${Date.now()}`, argv.target);
} else if (argv.action === 'move') {
    command = createMoveCommand(`move-${Date.now()}`, argv.forward, argv.strafe, argv.jump, argv.sprint);
} else if (argv.action === 'equip_hotbar') {
    command = createEquipHotbarCommand(`equip-${Date.now()}`, argv.slot ?? 0);
} else if (argv.action === 'use_skill') {
    command = createUseSkillCommand(`skill-${Date.now()}`, argv.skill_slot);
} else {
    command = createStopAllCommand(`stop-${Date.now()}`);
}

await sendBridgeCommand({ host: argv.host, port: argv.port }, argv.client, command);
console.log(`[client-bridge] sent ${argv.action} to ${argv.client}`);
