// @ts-nocheck
import fs from 'fs';
import path from 'path';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import settings from '../settings.ts';
import { OpenVoiceLocalTtsAdapter } from '../src/voice/providers/openVoiceLocal.js';
import { playAudioBuffer } from '../src/voice/localAudioPlayer.js';

function listAudioFiles(rootDir) {
    const results = [];
    const exts = new Set(['.wav', '.mp3', '.m4a']);

    function walk(dir) {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                walk(full);
            } else if (exts.has(path.extname(entry.name).toLowerCase())) {
                results.push(full);
            }
        }
    }

    walk(rootDir);
    return results;
}

const argv = await yargs(hideBin(process.argv))
    .option('list', {
        type: 'boolean',
        default: false,
        describe: 'List configured voice profiles'
    })
    .option('scan-audio', {
        type: 'boolean',
        default: false,
        describe: 'Scan the workspace for candidate reference audio files'
    })
    .option('profile', {
        type: 'string',
        default: '',
        describe: 'Profile to preview'
    })
    .option('text', {
        type: 'string',
        default: 'Voice profile preview.',
        describe: 'Preview text'
    })
    .option('play', {
        type: 'boolean',
        default: false,
        describe: 'Play the generated preview locally'
    })
    .help()
    .parse();

const openvoice = settings.voice?.openvoice || {};
const profiles = openvoice.profiles || {};

if (argv.list) {
    console.log('Configured voice profiles:');
    for (const [name, profile] of Object.entries(profiles)) {
        console.log(`- ${name}`);
        console.log(`  voiceName: ${profile.voiceName || openvoice.voice_name}`);
        console.log(`  language: ${profile.language || openvoice.language}`);
        console.log(`  referenceAudio: ${profile.referenceAudio || openvoice.reference_audio}`);
        console.log(`  zhVoiceName: ${profile.zhVoiceName || openvoice.zh_voice_name}`);
        console.log(`  zhLanguage: ${profile.zhLanguage || openvoice.zh_language}`);
        console.log(`  zhReferenceAudio: ${profile.zhReferenceAudio || openvoice.zh_reference_audio}`);
    }
}

if (argv['scan-audio']) {
    console.log('Discovered audio files:');
    for (const file of listAudioFiles(process.cwd())) {
        console.log(`- ${file}`);
    }
}

if (argv.profile) {
    const adapter = new OpenVoiceLocalTtsAdapter(openvoice);
    const result = await adapter.synthesize({
        text: argv.text,
        channel: 'chat',
        metadata: {
            voiceProfile: argv.profile
        }
    });

    console.log(`Generated ${result.audio.length} bytes using profile '${argv.profile}'.`);
    if (argv.play) {
        await playAudioBuffer(result.audio, result.mimeType);
        console.log('Playback finished.');
    }
}
