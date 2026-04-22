// @ts-nocheck
import fs from 'fs/promises';
import path from 'path';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import settings from '../settings.ts';
import { DoubaoAsrClient } from '../src/voice/providers/doubaoVoice.js';
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
const client = settings.voice?.doubao?.mode === 'realtime'
    ? new DoubaoRealtimeAsrClient(settings.voice?.doubao || {})
    : new DoubaoAsrClient(settings.voice?.doubao || {});
const result = client.recognizePcm
    ? await client.recognizePcm(audioBuffer)
    : await client.recognize({
        audioBuffer,
        audioFormat,
        speakerId: argv['speaker-id']
    });

console.log(JSON.stringify(result, null, 2));
