// @ts-nocheck
import { getKey, hasKey } from '../../utils/keys.js';

function getConfiguredDoubaoValue(directValue, envName) {
    if (directValue) {
        return directValue;
    }
    if (hasKey(envName)) {
        return getKey(envName);
    }
    return '';
}

export function buildDoubaoAuthHeaders({ apiKey }) {
    return {
        'X-Api-Key': apiKey,
        'Content-Type': 'application/json'
    };
}

export function buildDoubaoV3TtsPayload({
    model = 'seed-audio-1.0',
    text,
    textPrompt,
    format = 'mp3',
    sampleRate = 48000,
    pitchRate = 0,
    speechRate = 0,
    loudnessRate = 0,
    watermark = {}
}) {
    return {
        model,
        text_prompt: textPrompt || text,
        audio_config: {
            format,
            sample_rate: sampleRate,
            pitch_rate: pitchRate,
            speech_rate: speechRate,
            loudness_rate: loudnessRate
        },
        watermark
    };
}

function resolveDoubaoProfile(request = {}, config = {}) {
    const profiles = config.profiles || {};
    const requestedProfile = request.metadata?.voiceProfile;
    const selectedName = requestedProfile || config.activeProfile || (profiles.default ? 'default' : null);
    const selected = (selectedName && profiles[selectedName]) || {};
    return {
        name: selectedName || 'default',
        speakerId: selected.speakerId || config.speakerId || config.voiceName || '',
        encoding: selected.encoding || config.encoding || 'mp3',
        resourceId: selected.resourceId || config.resourceId || 'volc.service_type.10029'
    };
}

export class DoubaoVoiceAdapter {
    constructor(config = {}) {
        this.apiKey = getConfiguredDoubaoValue(config.apiKey, 'DOUBAO_API_KEY');
        this.cluster = config.cluster || process.env.DOUBAO_TTS_CLUSTER || 'volcano_icl';
        this.speakerId = config.speakerId || process.env.DOUBAO_SPEAKER_ID || '';
        this.encoding = config.encoding || 'mp3';
        this.model = config.model || process.env.DOUBAO_TTS_MODEL || 'seed-audio-1.0';
        this.endpoint = config.endpoint || 'https://openspeech.bytedance.com/api/v3/tts/create';
        this.sampleRate = config.sampleRate || 48000;
        this.pitchRate = config.pitchRate || 0;
        this.speechRate = config.speechRate || 0;
        this.loudnessRate = config.loudnessRate || 0;
        this.watermark = config.watermark || {};
        this.resourceId = config.resourceId || 'volc.service_type.10029';
        this.fetchImpl = config.fetchImpl || fetch;
        this.profiles = config.profiles || {};
        this.activeProfile = config.activeProfile || (this.profiles.default ? 'default' : null);
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

    async synthesize(request, options = {}) {
        const profile = resolveDoubaoProfile(request, {
            profiles: this.profiles,
            activeProfile: this.activeProfile,
            speakerId: this.speakerId,
            encoding: this.encoding,
            resourceId: this.resourceId
        });

        if (!this.apiKey) {
            throw new Error('Doubao v3 TTS requires DOUBAO_API_KEY.')
        }

        const payload = buildDoubaoV3TtsPayload({
            model: this.model,
            text: request.text,
            textPrompt: request.metadata?.textPrompt,
            format: profile.encoding,
            sampleRate: request.metadata?.sampleRate || this.sampleRate,
            pitchRate: request.metadata?.pitchRate ?? this.pitchRate,
            speechRate: request.metadata?.speechRate ?? this.speechRate,
            loudnessRate: request.metadata?.loudnessRate ?? this.loudnessRate,
            watermark: request.metadata?.watermark || this.watermark
        });

        const response = await this.fetchImpl(this.endpoint, {
            method: 'POST',
            headers: buildDoubaoAuthHeaders({
                apiKey: this.apiKey,
                resourceId: profile.resourceId,
                useApiKey: true
            }),
            body: JSON.stringify(payload),
            ...(options.signal ? { signal: options.signal } : {})
        });

        if (!response.ok) {
            throw new Error(`Doubao TTS request failed with status ${response.status}`);
        }

        const body = await response.json();
        if ((body.code && body.code !== 3000) || (body.BaseResp && body.BaseResp.StatusCode !== 0)) {
            throw new Error(body.message || body.BaseResp?.StatusMessage || 'Doubao TTS returned an error');
        }

        const base64Audio = typeof body.data === 'string'
            ? body.data
            : body.audio || body.result?.audio || body.data?.audio || body.result?.data;
        if (!base64Audio) {
            throw new Error('Doubao TTS response did not include audio data');
        }

        const mimeType = profile.encoding === 'wav' ? 'audio/wav' : 'audio/mp3';
        return {
            provider: 'doubao',
            audio: Buffer.from(base64Audio, 'base64'),
            mimeType
        };
    }
}
