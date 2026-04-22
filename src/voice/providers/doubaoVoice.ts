// @ts-nocheck
import { randomUUID } from 'crypto';
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

export function getDoubaoCloneResourceId(modelType = 5) {
    return modelType >= 4 ? 'volc.seedicl.voiceclone' : 'volc.megatts.voiceclone';
}

export function buildDoubaoAuthHeaders({ accessToken, apiKey, resourceId, useApiKey = true }) {
    if (useApiKey && apiKey) {
        return {
            'x-api-key': apiKey,
            'Content-Type': 'application/json'
        };
    }

    return {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
        'Resource-Id': resourceId,
        'X-Api-Resource-Id': resourceId
    };
}

export function buildDoubaoTtsPayload({
    appId,
    token,
    text,
    speakerId,
    encoding = 'mp3',
    cluster = 'volcano_icl',
    speedRatio = 1.0,
    loudnessRatio = 1.0,
    uid = 'game-ai',
    operation = 'query'
}) {
    const app = {
        cluster
    };

    if (appId) {
        app.appid = appId;
    }

    if (token) {
        app.token = token;
    }

    return {
        app,
        user: {
            uid
        },
        audio: {
            voice_type: speakerId,
            encoding,
            speed_ratio: speedRatio,
            loudness_ratio: loudnessRatio
        },
        request: {
            reqid: randomUUID(),
            text,
            text_type: 'plain',
            operation
        }
    };
}

export function buildDoubaoClonePayload({
    appId,
    speakerId,
    audioBuffer,
    audioFormat,
    text,
    language = 0,
    modelType = 5
}) {
    return {
        appid: appId,
        speaker_id: speakerId,
        audios: [
            {
                audio_bytes: audioBuffer.toString('base64'),
                audio_format: audioFormat,
                text
            }
        ],
        source: 2,
        language,
        model_type: modelType,
        extra_params: JSON.stringify({})
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
        this.appId = getConfiguredDoubaoValue(config.appId, 'DOUBAO_APP_ID');
        this.accessToken = getConfiguredDoubaoValue(config.accessToken, 'DOUBAO_ACCESS_TOKEN');
        this.apiKey = getConfiguredDoubaoValue(config.apiKey, 'DOUBAO_API_KEY');
        this.cluster = config.cluster || process.env.DOUBAO_TTS_CLUSTER || 'volcano_icl';
        this.speakerId = config.speakerId || process.env.DOUBAO_SPEAKER_ID || '';
        this.encoding = config.encoding || 'mp3';
        this.endpoint = config.endpoint || (this.apiKey
            ? 'https://openspeech.bytedance.com/api/v1/tts'
            : 'https://openspeech.bytedance.com/api/v3/tts/unidirectional');
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

    async synthesize(request) {
        const profile = resolveDoubaoProfile(request, {
            profiles: this.profiles,
            activeProfile: this.activeProfile,
            speakerId: this.speakerId,
            encoding: this.encoding,
            resourceId: this.resourceId
        });

        if ((!this.apiKey && (!this.appId || !this.accessToken)) || !profile.speakerId) {
            throw new Error('Doubao TTS requires speakerId plus either API key, or appId + accessToken.')
        }

        const response = await this.fetchImpl(this.endpoint, {
            method: 'POST',
            headers: buildDoubaoAuthHeaders({
                accessToken: this.accessToken,
                apiKey: this.apiKey,
                resourceId: profile.resourceId,
                useApiKey: !!this.apiKey
            }),
            body: JSON.stringify(buildDoubaoTtsPayload({
                appId: this.appId,
                token: this.apiKey ? '' : this.accessToken,
                text: request.text,
                speakerId: profile.speakerId,
                encoding: profile.encoding,
                cluster: this.cluster,
                operation: this.apiKey ? 'query' : 'submit'
            }))
        });

        if (!response.ok) {
            throw new Error(`Doubao TTS request failed with status ${response.status}`);
        }

        const body = await response.json();
        if ((body.code && body.code !== 3000) || (body.BaseResp && body.BaseResp.StatusCode !== 0)) {
            throw new Error(body.message || body.BaseResp?.StatusMessage || 'Doubao TTS returned an error');
        }

        const base64Audio = body.data || body.audio || body.result?.audio;
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

export class DoubaoVoiceCloneClient {
    constructor(config = {}) {
        this.appId = getConfiguredDoubaoValue(config.appId, 'DOUBAO_APP_ID');
        this.accessToken = getConfiguredDoubaoValue(config.accessToken, 'DOUBAO_ACCESS_TOKEN');
        this.apiKey = getConfiguredDoubaoValue(config.apiKey, 'DOUBAO_API_KEY');
        this.uploadEndpoint = config.uploadEndpoint || 'https://openspeech.bytedance.com/api/v1/mega_tts/audio/upload';
        this.statusEndpoint = config.statusEndpoint || 'https://openspeech.bytedance.com/api/v1/mega_tts/status';
        this.fetchImpl = config.fetchImpl || fetch;
    }

    async upload({ speakerId, audioBuffer, audioFormat = 'mp3', text, language = 0, modelType = 5 }) {
        const resourceId = getDoubaoCloneResourceId(modelType);
        const response = await this.fetchImpl(this.uploadEndpoint, {
            method: 'POST',
            headers: buildDoubaoAuthHeaders({
                accessToken: this.accessToken,
                apiKey: this.apiKey,
                resourceId,
                useApiKey: !!this.apiKey
            }),
            body: JSON.stringify(buildDoubaoClonePayload({
                appId: this.appId,
                speakerId,
                audioBuffer,
                audioFormat,
                text,
                language,
                modelType
            }))
        });

        if (!response.ok) {
            throw new Error(`Doubao clone upload failed with status ${response.status}`);
        }

        const body = await response.json();
        if (body.BaseResp?.StatusCode !== 0) {
            throw new Error(body.BaseResp?.StatusMessage || 'Doubao clone upload returned an error');
        }

        return {
            speakerId: body.speaker_id || speakerId,
            raw: body
        };
    }

    async getStatus({ speakerId, modelType = 5 }) {
        const resourceId = getDoubaoCloneResourceId(modelType);
        const response = await this.fetchImpl(this.statusEndpoint, {
            method: 'POST',
            headers: buildDoubaoAuthHeaders({
                accessToken: this.accessToken,
                apiKey: this.apiKey,
                resourceId,
                useApiKey: !!this.apiKey
            }),
            body: JSON.stringify({
                appid: this.appId,
                speaker_id: speakerId
            })
        });

        if (!response.ok) {
            throw new Error(`Doubao clone status failed with status ${response.status}`);
        }

        const body = await response.json();
        if (body.BaseResp?.StatusCode !== 0) {
            throw new Error(body.BaseResp?.StatusMessage || 'Doubao clone status returned an error');
        }

        return {
            speakerId: body.speaker_id || speakerId,
            status: body.status,
            raw: body
        };
    }
}

export class DoubaoAsrClient {
    constructor(config = {}) {
        this.appId = getConfiguredDoubaoValue(config.appId, 'DOUBAO_APP_ID');
        this.accessToken = getConfiguredDoubaoValue(config.accessToken, 'DOUBAO_ACCESS_TOKEN');
        this.apiKey = getConfiguredDoubaoValue(config.apiKey, 'DOUBAO_API_KEY');
        this.endpoint = config.endpoint || 'https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash';
        this.resourceId = config.resourceId || 'volc.bigasr.auc_turbo';
        this.fetchImpl = config.fetchImpl || fetch;
    }

    async recognize({ audioBuffer, audioFormat = 'mp3', speakerId = 'voice_user' }) {
        const requestId = randomUUID();
        const response = await this.fetchImpl(this.endpoint, {
            method: 'POST',
            headers: {
                Authorization: `Bearer ${this.apiKey || this.accessToken}`,
                'X-Api-App-Key': this.appId,
                'X-Api-Access-Key': this.apiKey || this.accessToken,
                'X-Api-Resource-Id': this.resourceId,
                'X-Api-Request-Id': requestId,
                'X-Api-Sequence': '-1',
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                user: {
                    uid: speakerId
                },
                audio: {
                    format: audioFormat,
                    data: audioBuffer.toString('base64')
                },
                request: {
                    model_name: 'bigmodel'
                }
            })
        });

        if (!response.ok) {
            throw new Error(`Doubao ASR failed with status ${response.status}`);
        }

        const body = await response.json();
        return {
            text: body.result?.text || body.text || '',
            source: 'doubao-asr',
            raw: body
        };
    }
}
