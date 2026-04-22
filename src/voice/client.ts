// @ts-nocheck
import { io } from 'socket.io-client';

export function buildVoiceTranscriptPayload(input) {
    return {
        text: input.text || '',
        speakerId: input.speakerId || 'voice_user',
        source: input.source || 'cli',
        timestamp: input.timestamp || Date.now(),
        metadata: input.metadata || {}
    };
}

export async function sendVoiceTranscript(options) {
    const payload = buildVoiceTranscriptPayload(options);
    const socket = io(options.serverUrl || `http://localhost:${options.port || 8080}`);

    await new Promise((resolve, reject) => {
        socket.on('connect', resolve);
        socket.on('connect_error', reject);
    });

    socket.emit('voice-transcript', options.agentName, payload);
    socket.close();
    return payload;
}
