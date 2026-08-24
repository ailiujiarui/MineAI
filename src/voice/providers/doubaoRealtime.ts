// @ts-nocheck
import WebSocket from 'ws';
import { getKey, hasKey } from '../../utils/keys.js';

const DEFAULT_ENDPOINT = 'wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue';
const DEFAULT_MODEL = '1.2.6.1';

function getConfiguredValue(directValue, envName) {
    if (directValue) return directValue;
    if (hasKey(envName)) return getKey(envName);
    return '';
}

export function buildDoubaoDuplexHeaders({ apiKey }) {
    return {
        'X-Api-Key': apiKey
    };
}

export const buildDoubaoRealtimeConnectHeaders = buildDoubaoDuplexHeaders;

function buildAudioFormat(type, sampleRate) {
    if (type && typeof type === 'object') return type;
    return {
        type,
        rate: sampleRate
    };
}

export function buildDoubaoSessionEvent({
    type = 'session.create',
    model = DEFAULT_MODEL,
    instructions = '',
    inputFormat = 'pcm',
    inputSampleRate = 16000,
    outputFormat = 'pcm_s16le',
    outputSampleRate = 24000,
    voice
} = {}) {
    const output = {
        format: buildAudioFormat(outputFormat, outputSampleRate)
    };
    if (voice) output.voice = voice;

    return {
        type,
        session: {
            model,
            instructions,
            audio: {
                input: {
                    format: buildAudioFormat(inputFormat, inputSampleRate)
                },
                output
            }
        }
    };
}

export const buildDoubaoRealtimeSessionEvent = buildDoubaoSessionEvent;

export function decodeDoubaoRealtimeMessage(raw) {
    if (raw === undefined || raw === null) {
        throw new Error('Doubao realtime message is empty');
    }
    if (typeof raw === 'string') return JSON.parse(raw);
    return JSON.parse(Buffer.from(raw).toString('utf8'));
}

class DoubaoRealtimeClient {
    constructor(config = {}) {
        this.apiKey = getConfiguredValue(config.apiKey, 'DOUBAO_API_KEY');
        this.endpoint = config.endpoint || config.realtime?.endpoint || DEFAULT_ENDPOINT;
        this.wsFactory = config.wsFactory || ((url, options) => new WebSocket(url, options));
    }

    async connect() {
        if (!this.apiKey) {
            throw new Error('Doubao duplex realtime dialogue requires DOUBAO_API_KEY.');
        }

        const socket = this.wsFactory(this.endpoint, {
            headers: buildDoubaoRealtimeConnectHeaders({ apiKey: this.apiKey })
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
                response.on('data', (chunk) => { body += String(chunk); });
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

        return socket;
    }

    send(socket, event) {
        socket.send(JSON.stringify(event));
    }

    waitForEvent(socket, predicate, timeoutMs = 30000, signal) {
        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                cleanup();
                reject(new Error('Timed out waiting for Doubao duplex realtime event'));
            }, timeoutMs);
            const cleanup = () => {
                clearTimeout(timeout);
                socket.off('message', onMessage);
                socket.off('error', onError);
                signal?.removeEventListener?.('abort', onAbort);
            };
            const onError = (error) => {
                cleanup();
                reject(error);
            };
            const onAbort = () => {
                cleanup();
                const error = new Error('Doubao realtime request aborted');
                error.name = 'AbortError';
                reject(error);
            };
            const onMessage = (raw) => {
                try {
                    const event = decodeDoubaoRealtimeMessage(raw);
                    if (event.type === 'error') {
                        cleanup();
                        reject(new Error(event.error?.message || event.message || 'Doubao realtime error'));
                        return;
                    }
                    if (predicate(event)) {
                        cleanup();
                        resolve(event);
                    }
                } catch (error) {
                    cleanup();
                    reject(error);
                }
            };

            socket.on('message', onMessage);
            socket.on('error', onError);
            signal?.addEventListener?.('abort', onAbort, { once: true });
            if (signal?.aborted) onAbort();
        });
    }
}

export class DoubaoRealtimeAsrClient {
    constructor(config = {}) {
        this.client = new DoubaoRealtimeClient(config);
        this.model = config.realtime?.model || config.model || DEFAULT_MODEL;
        this.instructions = config.realtime?.instructions || config.instructions || '';
        this.chunkSize = config.chunkSize || 640;
        this.chunkIntervalMs = config.chunkIntervalMs || 20;
        this.sampleRate = config.realtime?.inputSampleRate || config.sampleRate || 16000;
        this.inputFormat = config.realtime?.inputFormat || 'pcm';
    }

    async recognizePcm(audioBuffer) {
        const socket = await this.client.connect();
        try {
            this.client.send(socket, buildDoubaoRealtimeSessionEvent({
                model: this.model,
                instructions: this.instructions,
                inputFormat: this.inputFormat,
                inputSampleRate: this.sampleRate
            }));
            await this.client.waitForEvent(socket, (event) => event.type === 'session.created' || event.type === 'session.updated');

            for (let offset = 0; offset < audioBuffer.length; offset += this.chunkSize) {
                const chunk = audioBuffer.subarray(offset, Math.min(offset + this.chunkSize, audioBuffer.length));
                this.client.send(socket, {
                    type: 'input_audio_buffer.append',
                    audio: chunk.toString('base64')
                });
                if (this.chunkIntervalMs > 0) {
                    await new Promise((resolve) => setTimeout(resolve, this.chunkIntervalMs));
                }
            }
            this.client.send(socket, { type: 'input_audio_buffer.commit' });

            const result = await this.client.waitForEvent(socket, (event) =>
                event.type === 'conversation.item.input_audio_transcription.completed' ||
                event.type === 'input_audio_buffer.transcription.completed'
            );
            return {
                text: result.transcript || result.text || '',
                source: 'doubao-realtime-asr'
            };
        } finally {
            try { socket.close(); } catch {}
        }
    }

    async streamPcm(audioBuffer, onTranscript, options = {}) {
        const signal = options.signal;
        if (signal?.aborted) return;
        const socket = await this.client.connect();
        let finishStream;
        const abort = () => {
            try { socket.close(); } catch {}
            finishStream?.();
        };
        signal?.addEventListener?.('abort', abort, { once: true });
        try {
            if (signal?.aborted) return;
            this.client.send(socket, buildDoubaoRealtimeSessionEvent({
                model: this.model,
                instructions: this.instructions,
                inputFormat: this.inputFormat,
                inputSampleRate: this.sampleRate
            }));
            await this.client.waitForEvent(socket, (event) => event.type === 'session.created' || event.type === 'session.updated', 30000, signal);
            if (signal?.aborted) return;
            let finish;
            let fail;
            let settled = false;
            const callbackTasks = [];
            const finalEvent = new Promise((resolve, reject) => {
                finish = (value) => { if (!settled) { settled = true; resolve(value); } };
                fail = (error) => { if (!settled) { settled = true; reject(error); } };
            });
            finishStream = () => finish({ aborted: true });
            const seen = new Set();
            const handler = (raw) => {
                if (signal?.aborted) return;
                let event;
                try { event = decodeDoubaoRealtimeMessage(raw); } catch (error) {
                    fail(error);
                    return;
                }
                if (event.type === 'error') {
                    fail(new Error(event.error?.message || event.message || 'Doubao realtime error'));
                    return;
                }
                const text = event.transcript || event.text || event.delta || event.data?.text || '';
                const type = String(event.type || '');
                const final = type.includes('completed') || type.includes('committed') || event.final === true;
                const key = `${final ? 'final' : 'partial'}:${String(text)}`;
                if (text && !seen.has(key)) {
                    seen.add(key);
                    try {
                        callbackTasks.push(Promise.resolve(onTranscript({ text: String(text), final, eventType: type })));
                    } catch (error) { fail(error); return; }
                }
                if (final) finish();
            };
            const onError = (error) => { if (!signal?.aborted) fail(error); };
            const onClose = () => {
                if (!signal?.aborted && !settled) fail(new Error('Doubao realtime socket closed before final transcript'));
            };
            socket.on('message', handler);
            socket.on('error', onError);
            socket.on('close', onClose);
            try {
                for (let offset = 0; offset < audioBuffer.length; offset += this.chunkSize) {
                    if (signal?.aborted) return;
                    const chunk = audioBuffer.subarray(offset, Math.min(offset + this.chunkSize, audioBuffer.length));
                    this.client.send(socket, { type: 'input_audio_buffer.append', audio: chunk.toString('base64') });
                    if (this.chunkIntervalMs > 0) await new Promise(resolve => setTimeout(resolve, this.chunkIntervalMs));
                }
                this.client.send(socket, { type: 'input_audio_buffer.commit' });
                await finalEvent;
                await Promise.all(callbackTasks);
            } finally {
                socket.off('message', handler);
                socket.off('error', onError);
                socket.off('close', onClose);
            }
        } finally {
            signal?.removeEventListener?.('abort', abort);
            try { socket.close(); } catch {}
        }
    }
}
