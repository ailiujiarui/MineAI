// @ts-nocheck
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { OpenVoiceLocalTtsAdapter } from '../src/voice/providers/openVoiceLocal.js';
import { playAudioBuffer } from '../src/voice/localAudioPlayer.js';

const argv = await yargs(hideBin(process.argv))
    .option('text', {
        type: 'string',
        demandOption: true,
        describe: 'Text to synthesize'
    })
    .option('profile', {
        type: 'string',
        default: '',
        describe: 'Optional named OpenVoice profile to use'
    })
    .option('play', {
        type: 'boolean',
        default: false,
        describe: 'Play the generated audio locally after synthesis'
    })
    .help()
    .parse();

const adapter = new OpenVoiceLocalTtsAdapter();
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
