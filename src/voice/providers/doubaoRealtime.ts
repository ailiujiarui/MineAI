// @ts-nocheck
import { randomUUID } from 'crypto';
import WebSocket from 'ws';
import { getKey, hasKey } from '../../utils/keys.js';

const MESSAGE_TYPES = {
    FULL_CLIENT: 0x1,
    AUDIO_CLIENT: 0x2,
    FULL_SERVER: 0x9,
    AUDIO_SERVER: 0xb,
    ERROR: 0xf
};

const FLAG_EVENT = 0x4;
const DEFAULT_ENDPOINT = 'wss://openspeech.bytedance.com/api/v3/realtime/dialogue';
const DEFAULT_RESOURCE_ID = 'volc.speech.dialog';
const DEFAULT_APP_KEY = 'PlgvMymc7f3tQnJ6';
const DEFAULT_MODEL = '2.2.0.0';

function getConfiguredValue(directValue, envName) {
    if (directValue) return directValue;
    if (hasKey(envName)) return getKey(envName);
    return '';
}

function encodeHeader(messageType, flags, serialization = 1, compression = 0) {
    return Buffer.from([
        0x11,
        ((messageType & 0x0f) << 4) | (flags & 0x0f),
        ((serialization & 0x0f) << 4) | (compression & 0x0f),
        0x00
    ]);
}

function writeInt32(value) {
    const buffer = Buffer.alloc(4);
    buffer.writeInt32BE(value, 0);
    return buffer;
}

function encodeSessionParts(sessionId) {
    const sessionBuffer = Buffer.from(sessionId, 'utf8');
    return [writeInt32(sessionBuffer.length), sessionBuffer];
}

function encodeJsonPayload(messageType, eventId, payload = {}, options = {}) {
    const payloadBuffer = Buffer.from(JSON.stringify(payload), 'utf8');
    const parts = [encodeHeader(messageType, FLAG_EVENT, 1, 0), writeInt32(eventId)];

    if (options.sessionId) {
        parts.push(...encodeSessionParts(options.sessionId));
    }

    parts.push(writeInt32(payloadBuffer.length), payloadBuffer);
    return Buffer.concat(parts);
}

function encodeAudioPayload(eventId, audioBuffer, options = {}) {
    const parts = [encodeHeader(MESSAGE_TYPES.AUDIO_CLIENT, FLAG_EVENT, 0, 0), writeInt32(eventId)];
    if (options.sessionId) {
        parts.push(...encodeSessionParts(options.sessionId));
    }
    parts.push(writeInt32(audioBuffer.length), audioBuffer);
    return Buffer.concat(parts);
}

export function buildDoubaoRealtimeConnectHeaders({ appId, accessToken, resourceId = DEFAULT_RESOURCE_ID, appKey = DEFAULT_APP_KEY, connectId }) {
    const headers = {
        'X-Api-App-ID': appId,
        'X-Api-Access-Key': accessToken,
        'X-Api-Resource-Id': resourceId,
        'X-Api-App-Key': appKey
    };
    if (connectId) {
        headers['X-Api-Connect-Id'] = connectId;
    }
    return headers;
}

export function decodeDoubaoRealtimeFrame(buffer) {
    const messageType = (buffer[1] >> 4) & 0x0f;
    const flags = buffer[1] & 0x0f;
    const serialization = (buffer[2] >> 4) & 0x0f;

    let offset = 4;
    const frame = {
        messageType,
        flags,
        serialization,
        event: null,
        connectId: null,
        sessionId: null,
        payload: null
    };

    if (flags === FLAG_EVENT) {
        frame.event = buffer.readInt32BE(offset);
        offset += 4;

        if (frame.event < 100) {
            const maybeSize = buffer.readInt32BE(offset);
            const remainingAfterMaybeSize = buffer.length - (offset + 4);
            if (maybeSize > 0 && remainingAfterMaybeSize > maybeSize + 3) {
                offset += 4;
                frame.connectId = buffer.subarray(offset, offset + maybeSize).toString('utf8');
                offset += maybeSize;
            }
        } else {
            const sessionSize = buffer.readInt32BE(offset);
            offset += 4;
            frame.sessionId = buffer.subarray(offset, offset + sessionSize).toString('utf8');
            offset += sessionSize;
        }
    }

    const payloadSize = buffer.readInt32BE(offset);
    offset += 4;
    const payloadBuffer = buffer.subarray(offset, offset + payloadSize);
    frame.payload = serialization === 1
        ? JSON.parse(payloadBuffer.toString('utf8'))
        : payloadBuffer;
    return frame;
}

function pcm16leToWav(pcmBuffer, sampleRate = 24000, channels = 1, bitsPerSample = 16) {
    const blockAlign = channels * (bitsPerSample / 8);
    const byteRate = sampleRate * blockAlign;
    const dataSize = pcmBuffer.length;
    const header = Buffer.alloc(44);
    header.write('RIFF', 0);
    header.writeUInt32LE(36 + dataSize, 4);
    header.write('WAVE', 8);
    header.write('fmt ', 12);
    header.writeUInt32LE(16, 16);
    header.writeUInt16LE(1, 20);
    header.writeUInt16LE(channels, 22);
    header.writeUInt32LE(sampleRate, 24);
    header.writeUInt32LE(byteRate, 28);
    header.writeUInt16LE(blockAlign, 32);
    header.writeUInt16LE(bitsPerSample, 34);
    header.write('data', 36);
    header.writeUInt32LE(dataSize, 40);
    return Buffer.concat([header, pcmBuffer]);
}

class DoubaoRealtimeClient {
    constructor(config = {}) {
        this.appId = getConfiguredValue(config.appId, 'DOUBAO_APP_ID');
        this.accessToken = getConfiguredValue(config.accessToken, 'DOUBAO_ACCESS_TOKEN');
        this.endpoint = config.endpoint || config.realtime?.endpoint || DEFAULT_ENDPOINT;
        this.resourceId = config.realtime?.resourceId || config.resourceId || DEFAULT_RESOURCE_ID;
        this.appKey = config.realtime?.appKey || config.appKey || DEFAULT_APP_KEY;
        this.wsFactory = config.wsFactory || ((url, options) => new WebSocket(url, options));
    }

    async connect() {
        if (!this.appId || !this.accessToken) {
            throw new Error('Doubao realtime dialogue requires DOUBAO_APP_ID and DOUBAO_ACCESS_TOKEN.');
        }

        const connectId = randomUUID();
        const socket = this.wsFactory(this.endpoint, {
            headers: buildDoubaoRealtimeConnectHeaders({
                appId: this.appId,
                accessToken: this.accessToken,
                resourceId: this.resourceId,
                appKey: this.appKey,
                connectId
            })
        });

        await new Promise((resolve, reject) => {
            const cleanup = () => {
                socket.off('open', onOpen);
                socket.off('unexpected-response', onUnexpectedResponse);
                socket.off('error', onError);
            };

            const onOpen = () => {
                cleanup();
                resolve();
            };

            const onUnexpectedResponse = (_request, response) => {
                let body = '';
                response.on('data', (chunk) => {
                    body += String(chunk);
                });
                response.on('end', () => {
                    cleanup();
                    reject(new Error(`Doubao realtime handshake failed: ${response.statusCode} ${response.statusMessage || ''} ${body}`.trim()));
                });
            };

            const onError = (error) => {
                cleanup();
                reject(error);
            };

            socket.on('open', onOpen);
            socket.on('unexpected-response', onUnexpectedResponse);
            socket.on('error', onError);
        });

        return { socket, sessionId: randomUUID() };
    }

    async waitForEvent(socket, predicate, timeoutMs = 30000) {
        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                cleanup();
                reject(new Error('Timed out waiting for Doubao realtime dialogue event'));
            }, timeoutMs);

            const cleanup = () => {
                clearTimeout(timeout);
                socket.off('message', onMessage);
                socket.off('error', onError);
            };

            const onMessage = (raw) => {
                try {
                    const frame = decodeDoubaoRealtimeFrame(Buffer.from(raw));
                    if (predicate(frame)) {
                        cleanup();
                        resolve(frame);
                    }
                } catch (error) {
                    cleanup();
                    reject(error);
                }
            };

            const onError = (error) => {
                cleanup();
                reject(error);
            };

            socket.on('message', onMessage);
            socket.on('error', onError);
        });
    }
}

export class DoubaoRealtimeTtsAdapter {
    constructor(config = {}) {
        this.client = new DoubaoRealtimeClient(config);
        this.model = config.realtime?.model || config.model || DEFAULT_MODEL;
        this.speakerId = config.speakerId || '';
        this.sampleRate = config.sampleRate || config.realtime?.sampleRate || 24000;
        this.audioFormat = config.realtime?.audioFormat || config.audioFormat || 'pcm_s16le';
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

    resolveProfile(request = {}) {
        const requested = request.metadata?.voiceProfile;
        const name = requested || this.activeProfile || 'default';
        const selected = this.profiles[name] || {};
        return {
            name,
            speakerId: selected.speakerId || this.speakerId,
            model: selected.model || this.model,
            sampleRate: selected.sampleRate || this.sampleRate,
            audioFormat: selected.audioFormat || this.audioFormat
        };
    }

    async synthesize(request) {
        const profile = this.resolveProfile(request);
        if (!profile.speakerId) {
            throw new Error('Doubao realtime TTS requires a speakerId.');
        }

        const { socket, sessionId } = await this.client.connect();
        const audioChunks = [];

        try {
            socket.send(encodeJsonPayload(MESSAGE_TYPES.FULL_CLIENT, 1, {}));
            await this.client.waitForEvent(socket, (frame) => frame.event === 50);

            socket.send(encodeJsonPayload(MESSAGE_TYPES.FULL_CLIENT, 100, {
                tts: {
                    speaker: profile.speakerId,
                    audio_config: {
                        channel: 1,
                        format: profile.audioFormat,
                        sample_rate: profile.sampleRate
                    }
                },
                dialog: {
                    extra: {
                        input_mod: 'text',
                        model: profile.model
                    }
                }
            }, { sessionId }));
            await this.client.waitForEvent(socket, (frame) => frame.event === 150);

            socket.send(encodeJsonPayload(MESSAGE_TYPES.FULL_CLIENT, 300, {
                content: request.text
            }, { sessionId }));

            await new Promise((resolve, reject) => {
                const timeout = setTimeout(() => {
                    cleanup();
                    reject(new Error('Timed out waiting for realtime TTS audio'));
                }, 30000);

                const cleanup = () => {
                    clearTimeout(timeout);
                    socket.off('message', onMessage);
                    socket.off('error', onError);
                };

                const onError = (error) => {
                    cleanup();
                    reject(error);
                };

                const onMessage = (raw) => {
                    try {
                        const frame = decodeDoubaoRealtimeFrame(Buffer.from(raw));
                        if (frame.messageType === MESSAGE_TYPES.AUDIO_SERVER && frame.event === 352 && Buffer.isBuffer(frame.payload)) {
                            audioChunks.push(frame.payload);
                        }
                        if (frame.event === 359) {
                            cleanup();
                            resolve();
                            return;
                        }
                        if (frame.event === 599 || frame.event === 153 || frame.event === 51) {
                            cleanup();
                            reject(new Error(frame.payload?.message || frame.payload?.error || 'Doubao realtime TTS error'));
                        }
                    } catch (error) {
                        cleanup();
                        reject(error);
                    }
                };

                socket.on('message', onMessage);
                socket.on('error', onError);
            });

            socket.send(encodeJsonPayload(MESSAGE_TYPES.FULL_CLIENT, 102, {}, { sessionId }));
            const audio = pcm16leToWav(Buffer.concat(audioChunks), profile.sampleRate, 1, 16);
            return {
                provider: 'doubao-realtime',
                audio,
                mimeType: 'audio/wav'
            };
        } finally {
            try { socket.close(); } catch {}
        }
    }
}

export class DoubaoRealtimeAsrClient {
    constructor(config = {}) {
        this.client = new DoubaoRealtimeClient(config);
        this.model = config.realtime?.model || config.model || DEFAULT_MODEL;
        this.chunkSize = config.chunkSize || 640;
        this.chunkIntervalMs = config.chunkIntervalMs || 20;
        this.sampleRate = config.sampleRate || config.realtime?.sampleRate || 16000;
    }

    async recognizePcm(audioBuffer) {
        const { socket, sessionId } = await this.client.connect();
        let finalText = '';

        try {
            socket.send(encodeJsonPayload(MESSAGE_TYPES.FULL_CLIENT, 1, {}));
            await this.client.waitForEvent(socket, (frame) => frame.event === 50);

            socket.send(encodeJsonPayload(MESSAGE_TYPES.FULL_CLIENT, 100, {
                asr: {
                    audio_info: {
                        format: 'pcm',
                        sample_rate: this.sampleRate,
                        channel: 1
                    }
                },
                dialog: {
                    extra: {
                        input_mod: 'audio_file',
                        model: this.model
                    }
                }
            }, { sessionId }));
            await this.client.waitForEvent(socket, (frame) => frame.event === 150);

            for (let offset = 0; offset < audioBuffer.length; offset += this.chunkSize) {
                const chunk = audioBuffer.subarray(offset, Math.min(offset + this.chunkSize, audioBuffer.length));
                socket.send(encodeAudioPayload(200, chunk, { sessionId }));
                if (this.chunkIntervalMs > 0) {
                    await new Promise((resolve) => setTimeout(resolve, this.chunkIntervalMs));
                }
            }

            socket.send(encodeJsonPayload(MESSAGE_TYPES.FULL_CLIENT, 400, {}, { sessionId }));

            await new Promise((resolve, reject) => {
                const timeout = setTimeout(() => {
                    cleanup();
                    reject(new Error('Timed out waiting for realtime ASR result'));
                }, 30000);

                const cleanup = () => {
                    clearTimeout(timeout);
                    socket.off('message', onMessage);
                    socket.off('error', onError);
                };

                const onError = (error) => {
                    cleanup();
                    reject(error);
                };

                const onMessage = (raw) => {
                    try {
                        const frame = decodeDoubaoRealtimeFrame(Buffer.from(raw));
                        if (frame.event === 451) {
                            const latest = frame.payload?.results?.at?.(-1);
                            if (latest?.text && !latest.is_interim) {
                                finalText = latest.text;
                            }
                        }
                        if (frame.event === 459) {
                            cleanup();
                            resolve();
                            return;
                        }
                        if (frame.event === 599 || frame.event === 153 || frame.event === 51) {
                            cleanup();
                            reject(new Error(frame.payload?.message || frame.payload?.error || 'Doubao realtime ASR error'));
                        }
                    } catch (error) {
                        cleanup();
                        reject(error);
                    }
                };

                socket.on('message', onMessage);
                socket.on('error', onError);
            });

            socket.send(encodeJsonPayload(MESSAGE_TYPES.FULL_CLIENT, 102, {}, { sessionId }));
            return {
                text: finalText,
                source: 'doubao-realtime-asr'
            };
        } finally {
            try { socket.close(); } catch {}
        }
    }
}
