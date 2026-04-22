import {
    type AutonomyEmbodimentSignal,
    type CompanionEmbodimentSignal,
    type EmbodimentProfile,
    type EmbodimentState,
    type VoiceEmbodimentSignal
} from './embodimentTypes.js';

export function createEmbodimentRuntime(config: { profile: EmbodimentProfile }) {
    const state: EmbodimentState = {
        profile: config.profile,
        presence: 'idle',
        emotion: 'neutral',
        action: 'idle',
        voiceStyle: null
    };

    return {
        setProfile(profile: EmbodimentProfile) {
            state.profile = profile;
        },
        applyAutonomySignal(signal: AutonomyEmbodimentSignal) {
            state.action = signal.state === 'tasking' ? 'tasking' : signal.state;
            state.presence = 'tasking';
            if (signal.urgency === 'focused') {
                state.emotion = 'focused';
            }
            if (signal.urgency === 'concerned') {
                state.emotion = 'concerned';
                state.presence = 'danger';
            }
        },
        applyCompanionSignal(signal: CompanionEmbodimentSignal) {
            if (signal.expression) {
                state.emotion = signal.expression;
            } else if (signal.mood) {
                state.emotion = signal.mood;
            }
        },
        applyVoiceSignal(signal: VoiceEmbodimentSignal) {
            state.voiceStyle = signal.style;
            state.presence = signal.speaking ? 'speaking' : 'idle';
        },
        clearTransientSignals() {
            state.presence = 'idle';
            state.voiceStyle = null;
        },
        getState() {
            return {
                ...state,
                profile: { ...state.profile }
            };
        }
    };
}
