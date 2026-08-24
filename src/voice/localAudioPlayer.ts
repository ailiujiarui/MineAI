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
    const signal = deps.signal;

    if (signal?.aborted) {
        const error = new Error('Audio playback was aborted');
        error.name = 'AbortError';
        throw error;
    }

    const plan = createPlaybackPlan(mimeType);
    const tempDir = await mkdtempImpl(path.join(os.tmpdir(), 'game-ai-voice-'));
    const audioPath = path.join(tempDir, `voice${plan.extension}`);
    await writeFileImpl(audioPath, audio);
    if (signal?.aborted) {
        const error = new Error('Audio playback was aborted');
        error.name = 'AbortError';
        try { await unlinkImpl(audioPath); } catch {}
        throw error;
    }

    await new Promise((resolve, reject) => {
        let child;
        let settled = false;
        const abortError = () => {
            const error = new Error('Audio playback was aborted');
            error.name = 'AbortError';
            return error;
        };
        const finish = (callback, value) => {
            if (settled) return;
            settled = true;
            signal?.removeEventListener?.('abort', onAbort);
            callback(value);
        };
        const onAbort = () => {
            finish(reject, abortError());
            try { child?.kill?.(); } catch {}
        };
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
            if (code === 0 || code === null) finish(resolve);
            else finish(reject, new Error(`Audio player exited with code ${code}`));
        });
        child.on('error', (error) => finish(reject, error));
        signal?.addEventListener?.('abort', onAbort, { once: true });
        if (signal?.aborted) onAbort();
    }).finally(async () => {
        try {
            await unlinkImpl(audioPath);
        } catch {}
    });
}
