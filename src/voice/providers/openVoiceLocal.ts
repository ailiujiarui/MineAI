// @ts-nocheck
import { mkdtemp, readFile } from 'fs/promises';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';

export function detectOpenVoiceProfile(text, config = {}) {
    const hasCjk = /[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/.test(text || '');
    if (hasCjk) {
        return {
            voiceName: config.zhVoiceName || 'ZH',
            language: config.zhLanguage || 'ZH'
        };
    }
    return {
        voiceName: config.voiceName || 'EN-US',
        language: config.language || 'EN_V2'
    };
}

export function resolveOpenVoiceProfile(request = {}, config = {}) {
    const profiles = config.profiles || {};
    const requestedProfile = request.metadata?.voiceProfile;
    const selectedName = requestedProfile || config.activeProfile || (profiles.default ? 'default' : null);
    const selected = (selectedName && profiles[selectedName]) || {};
    const fallback = {
        referenceAudio: config.referenceAudio,
        voiceName: config.voiceName,
        language: config.language,
        zhVoiceName: config.zhVoiceName,
        zhLanguage: config.zhLanguage,
        zhReferenceAudio: config.zhReferenceAudio
    };
    const merged = {
        ...fallback,
        ...selected
    };
    const detected = detectOpenVoiceProfile(request.text, merged);
    const isZh = detected.language === (merged.zhLanguage || 'ZH');

    return {
        name: selectedName || 'default',
        voiceName: detected.voiceName,
        language: detected.language,
        referenceAudio: isZh
            ? (merged.zhReferenceAudio || merged.referenceAudio || '')
            : (merged.referenceAudio || '')
    };
}

export class OpenVoiceLocalTtsAdapter {
    constructor(config = {}) {
        this.pythonCommand = config.pythonCommand || process.env.OPENVOICE_PYTHON || '.\\.local\\openvoice-venv\\Scripts\\python.exe';
        this.scriptPath = config.scriptPath || process.env.OPENVOICE_TTS_SCRIPT || 'scripts/openvoice_tts.py';
        this.referenceAudio = config.referenceAudio || process.env.OPENVOICE_REFERENCE_AUDIO || '.\\.local\\OpenVoice\\resources\\example_reference.mp3';
        this.voiceName = config.voiceName || process.env.OPENVOICE_VOICE_NAME || 'EN-US';
        this.language = config.language || process.env.OPENVOICE_LANGUAGE || 'EN_V2';
        this.zhVoiceName = config.zhVoiceName || process.env.OPENVOICE_ZH_VOICE_NAME || 'ZH';
        this.zhLanguage = config.zhLanguage || process.env.OPENVOICE_ZH_LANGUAGE || 'ZH';
        this.zhReferenceAudio = config.zhReferenceAudio || process.env.OPENVOICE_ZH_REFERENCE_AUDIO || this.referenceAudio;
        this.profiles = config.profiles || {};
        this.activeProfile = config.activeProfile || process.env.OPENVOICE_ACTIVE_PROFILE || (this.profiles.default ? 'default' : null);
        this.spawnImpl = config.spawnImpl || spawn;
        this.readFileImpl = config.readFileImpl || readFile;
    }

    setActiveProfile(profileName) {
        if (profileName && this.profiles[profileName]) {
            this.activeProfile = profileName;
            return true;
        }
        return false;
    }

    getActiveProfile() {
        return this.activeProfile;
    }

    getAvailableProfiles() {
        return Object.keys(this.profiles);
    }

    async synthesize(request) {
        const tempDir = await mkdtemp(path.join(os.tmpdir(), 'openvoice-'));
        const outputPath = path.join(tempDir, 'output.wav');

        const profile = resolveOpenVoiceProfile(request, {
            voiceName: this.voiceName,
            language: this.language,
            zhVoiceName: this.zhVoiceName,
            zhLanguage: this.zhLanguage,
            referenceAudio: this.referenceAudio,
            zhReferenceAudio: this.zhReferenceAudio,
            profiles: this.profiles,
            activeProfile: this.activeProfile
        });

        const args = [
            this.scriptPath,
            '--text', request.text,
            '--output', outputPath,
            '--voice-name', profile.voiceName,
            '--language', profile.language
        ];

        if (profile.referenceAudio) {
            args.push('--reference-audio', profile.referenceAudio);
        }

        await new Promise((resolve, reject) => {
            const child = this.spawnImpl(this.pythonCommand, args);
            let stderr = '';
            child.stderr?.on?.('data', (chunk) => {
                stderr += String(chunk);
            });
            child.on('close', (code) => {
                if (code === 0) {
                    resolve();
                } else {
                    reject(new Error(`OpenVoice local TTS failed: ${stderr || code}`));
                }
            });
        });

        const audio = await this.readFileImpl(outputPath);
        return {
            provider: 'openvoice-local',
            audio,
            mimeType: 'audio/wav'
        };
    }
}
