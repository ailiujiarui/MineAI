// @ts-nocheck
import fs from 'fs/promises';
import path from 'path';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import settings from '../settings.ts';
import { DoubaoRealtimeAsrClient } from '../src/voice/providers/doubaoRealtime.js';

const argv = await yargs(hideBin(process.argv))
    .option('file', {
        type: 'string',
        demandOption: true,
        describe: 'Audio file to recognize'
    })
    .option('speaker-id', {
        type: 'string',
        default: 'voice_user',
        describe: 'Logical speaker id for the request'
    })
    .help()
    .parse();

const audioBuffer = await fs.readFile(argv.file);
const audioFormat = path.extname(argv.file).replace('.', '').toLowerCase();
const client = new DoubaoRealtimeAsrClient(settings.voice?.doubao || {});
const result = await client.recognizePcm(audioBuffer);

console.log(JSON.stringify(result, null, 2));
