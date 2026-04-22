export type EmbodimentProvider = 'ysm' | 'custom' | 'none';

export type EmbodimentProfile = {
    id: string;
    displayName: string;
    modelProvider: EmbodimentProvider;
    modelId: string;
};

export type EmbodimentPresence = 'idle' | 'tasking' | 'danger' | 'speaking';

export type EmbodimentEmotion = 'neutral' | 'happy' | 'warm' | 'concerned' | 'focused';

export type EmbodimentAction = 'idle' | 'tasking' | 'follow' | 'combat' | 'build';

export type EmbodimentState = {
    profile: EmbodimentProfile;
    presence: EmbodimentPresence;
    emotion: EmbodimentEmotion;
    action: EmbodimentAction;
    voiceStyle: string | null;
};

export type AutonomyEmbodimentSignal = {
    state: Exclude<EmbodimentAction, 'idle'> | 'tasking';
    urgency?: 'focused' | 'concerned';
};

export type CompanionEmbodimentSignal = {
    mood?: EmbodimentEmotion;
    expression?: Extract<EmbodimentEmotion, 'happy' | 'warm' | 'concerned' | 'focused'>;
};

export type VoiceEmbodimentSignal = {
    speaking: boolean;
    style: string;
};
