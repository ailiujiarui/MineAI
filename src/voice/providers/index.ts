// @ts-nocheck
import { NullTtsAdapter } from '../voiceRuntime.js';
import { DoubaoVoiceAdapter } from './doubaoVoice.js';
import { DoubaoRealtimeAsrClient } from './doubaoRealtime.js';

export function createTtsAdapter(settings = {}) {
    if (settings.provider === 'doubao') {
        return new DoubaoVoiceAdapter(settings.doubao || {});
    }
    return new NullTtsAdapter();
}

export function createAsrAdapter(settings = {}) {
    if (settings.provider === 'doubao') {
        return new DoubaoRealtimeAsrClient(settings.doubao || {});
    }
    return null;
}
