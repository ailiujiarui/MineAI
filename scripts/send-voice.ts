// @ts-nocheck
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { sendVoiceTranscript } from '../src/voice/client.js';

const argv = await yargs(hideBin(process.argv))
    .option('agent', {
        type: 'string',
        demandOption: true,
        describe: 'Target bot name'
    })
    .option('text', {
        type: 'string',
        demandOption: true,
        describe: 'Voice transcript text to inject'
    })
    .option('speaker', {
        type: 'string',
        default: 'voice_user',
        describe: 'Speaker id for the transcript'
    })
    .option('source', {
        type: 'string',
        default: 'cli',
        describe: 'Transcript source label'
    })
    .option('port', {
        type: 'number',
        default: 8080,
        describe: 'MindServer port'
    })
    .help()
    .parse();

const payload = await sendVoiceTranscript({
    agentName: argv.agent,
    text: argv.text,
    speakerId: argv.speaker,
    source: argv.source,
    port: argv.port
});

console.log(`Voice transcript sent to ${argv.agent}: ${payload.text}`);
