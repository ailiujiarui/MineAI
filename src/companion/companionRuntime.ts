// @ts-nocheck
import { buildCompanionReply, buildTaskFailureNarration, buildTaskNarration } from './companionPrompt.js';
import { createCompanionState } from './companionState.js';

export class CompanionRuntime {
    constructor(config = {}) {
        this.state = createCompanionState({
            mode: config.mode || 'task-with-companion-tone'
        });
    }

    setMode(mode) {
        this.state.mode = mode;
    }

    async describeTaskUpdate(update) {
        this.state.lastTaskStage = update.stage;
        this.state.lastTaskCommand = update.nextCommand || null;
        return buildTaskNarration(this.state, update);
    }

    async respondToCompanionInput(event) {
        this.state.lastSpeakerId = event.speakerId || null;
        return buildCompanionReply(this.state, event);
    }

    async describeTaskFailure(update) {
        this.state.lastTaskStage = update.stage;
        this.state.lastTaskCommand = update.failedCommand || null;
        return buildTaskFailureNarration(this.state, update);
    }
}
