import { type EmbodimentProfile, type EmbodimentState } from './embodimentTypes.js';

export class YsmAdapter {
    config: Record<string, unknown>;

    constructor(config = {}) {
        this.config = config;
    }

    supports(profile: EmbodimentProfile) {
        return profile.modelProvider === 'ysm';
    }

    mapState(state: EmbodimentState) {
        return {
            modelId: state.profile.modelId,
            animation: state.action,
            expression: state.emotion,
            speaking: state.presence === 'speaking',
            voiceStyle: state.voiceStyle
        };
    }
}
