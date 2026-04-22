// @ts-nocheck
import { mkdtemp, writeFile, unlink } from 'fs/promises';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';

export function createPlaybackPlan(mimeType) {
    return {
        extension: mimeType === 'audio/wav' ? '.wav' : '.mp3'
    };
}

export async function playAudioBuffer(audio, mimeType, deps = {}) {
    const writeFileImpl = deps.writeFileImpl || writeFile;
    const mkdtempImpl = deps.mkdtempImpl || mkdtemp;
    const unlinkImpl = deps.unlinkImpl || unlink;
    const spawnImpl = deps.spawnImpl || spawn;

    const plan = createPlaybackPlan(mimeType);
    const tempDir = await mkdtempImpl(path.join(os.tmpdir(), 'game-ai-voice-'));
    const audioPath = path.join(tempDir, `voice${plan.extension}`);
    await writeFileImpl(audioPath, audio);

    await new Promise((resolve, reject) => {
        let child;
        if (process.platform === 'win32' && plan.extension === '.wav') {
            const command = [
                '-NoProfile',
                '-Command',
                `(New-Object Media.SoundPlayer '${audioPath.replace(/'/g, "''")}').PlaySync()`
            ];
            child = spawnImpl('powershell.exe', command, { windowsHide: true });
        } else {
            child = spawnImpl('ffplay', ['-nodisp', '-autoexit', '-loglevel', 'quiet', audioPath], { windowsHide: true });
        }

        child.on('close', (code) => {
            if (code === 0 || code === null) resolve();
            else reject(new Error(`Audio player exited with code ${code}`));
        });
        child.on('error', reject);
    }).finally(async () => {
        try {
            await unlinkImpl(audioPath);
        } catch {}
    });
}
