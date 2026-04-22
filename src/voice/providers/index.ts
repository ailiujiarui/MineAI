// @ts-nocheck
import { NullTtsAdapter } from '../voiceRuntime.js';
import { DoubaoAsrClient, DoubaoVoiceAdapter } from './doubaoVoice.js';
import { DoubaoRealtimeAsrClient, DoubaoRealtimeTtsAdapter } from './doubaoRealtime.js';
import { OpenVoiceLocalTtsAdapter } from './openVoiceLocal.js';

export function createTtsAdapter(settings = {}) {
    if (settings.provider === 'openvoice-local') {
        return new OpenVoiceLocalTtsAdapter(settings.openvoice || {});
    }
    if (settings.provider === 'doubao') {
        if (settings.doubao?.mode === 'realtime') {
            return new DoubaoRealtimeTtsAdapter(settings.doubao || {});
        }
        return new DoubaoVoiceAdapter(settings.doubao || {});
    }
    return new NullTtsAdapter();
}

export function createAsrAdapter(settings = {}) {
    if (settings.provider === 'doubao') {
        if (settings.doubao?.mode === 'realtime') {
            return new DoubaoRealtimeAsrClient(settings.doubao || {});
        }
        return new DoubaoAsrClient(settings.doubao || {});
    }
    return null;
}
