// @ts-nocheck
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import settings from '../settings.ts';
import { createTtsAdapter } from '../src/voice/providers/index.js';
import { playAudioBuffer } from '../src/voice/localAudioPlayer.js';

const argv = await yargs(hideBin(process.argv))
    .option('text', {
        type: 'string',
        demandOption: true,
        describe: 'Text to synthesize with Doubao TTS'
    })
    .option('profile', {
        type: 'string',
        default: '',
        describe: 'Optional Doubao voice profile name'
    })
    .option('play', {
        type: 'boolean',
        default: false,
        describe: 'Play synthesized audio locally'
    })
    .help()
    .parse();

const adapter = createTtsAdapter(settings.voice || {});
const result = await adapter.synthesize({
    text: argv.text,
    channel: 'chat',
    metadata: {
        voiceProfile: argv.profile || undefined
    }
});

console.log(`Generated ${result.audio.length} bytes via ${result.provider}`);
if (argv.play) {
    await playAudioBuffer(result.audio, result.mimeType);
    console.log('Playback finished.');
}
