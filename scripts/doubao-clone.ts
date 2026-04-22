// @ts-nocheck
import fs from 'fs/promises';
import path from 'path';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import settings from '../settings.ts';
import { DoubaoVoiceCloneClient } from '../src/voice/providers/doubaoVoice.js';

const argv = await yargs(hideBin(process.argv))
    .option('speaker-id', {
        type: 'string',
        demandOption: true,
        describe: 'Speaker ID to create or query'
    })
    .option('file', {
        type: 'string',
        default: '',
        describe: 'Reference audio file for clone upload'
    })
    .option('text', {
        type: 'string',
        default: '',
        describe: 'Transcript matching the reference audio'
    })
    .option('audio-format', {
        type: 'string',
        default: '',
        describe: 'Audio format override, inferred from file extension when omitted'
    })
    .option('status', {
        type: 'boolean',
        default: false,
        describe: 'Query clone training status instead of uploading'
    })
    .help()
    .parse();

const doubao = settings.voice?.doubao || {};
const modelType = doubao.clone?.modelType || 5;
const language = doubao.clone?.language || 0;
const client = new DoubaoVoiceCloneClient(doubao);

if (argv.status) {
    const status = await client.getStatus({
        speakerId: argv['speaker-id'],
        modelType
    });
    console.log(JSON.stringify(status, null, 2));
    process.exit(0);
}

if (!argv.file || !argv.text) {
    throw new Error('Clone upload requires both --file and --text.');
}

const audioBuffer = await fs.readFile(argv.file);
const audioFormat = argv['audio-format'] || path.extname(argv.file).replace('.', '').toLowerCase();
const result = await client.upload({
    speakerId: argv['speaker-id'],
    audioBuffer,
    audioFormat,
    text: argv.text,
    language,
    modelType
});

console.log(JSON.stringify(result, null, 2));
