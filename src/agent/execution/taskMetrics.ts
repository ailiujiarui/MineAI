import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolveRunPath } from '../../utils/runContext.js';

export class TaskMetrics {
    private readonly filePath: string;
    private data: any = {
        actions: 0,
        successes: 0,
        failures: 0,
        failureReasons: {},
        recoveries: 0,
        stops: 0,
        stopLatencyMs: [],
        currentStage: null,
        stageTransitions: []
    };

    constructor(agentName: string) {
        const root = resolveRunPath('bots', agentName);
        mkdirSync(root, { recursive: true });
        this.filePath = `${root}/task-metrics.json`;
    }

    load() {
        try {
            if (existsSync(this.filePath)) this.data = { ...this.data, ...JSON.parse(readFileSync(this.filePath, 'utf8')) };
        } catch (error: any) {
            console.warn('[task-metrics] failed to load:', error?.message || error);
        }
        this.data.stopLatencyMs = Array.isArray(this.data.stopLatencyMs) ? this.data.stopLatencyMs.slice(-100) : [];
        this.data.stageTransitions = Array.isArray(this.data.stageTransitions) ? this.data.stageTransitions.slice(-20) : [];
    }

    recordFeedback(feedback: any) {
        this.data.actions += 1;
        if (feedback.success) this.data.successes += 1;
        else {
            this.data.failures += 1;
            const reason = feedback.failureReason || 'execution_error';
            this.data.failureReasons[reason] = (this.data.failureReasons[reason] || 0) + 1;
            if (reason === 'interrupted' || reason === 'timeout') this.data.recoveries += 1;
        }
        this.persist();
    }

    recordStop(latencyMs: number) {
        if (!Number.isFinite(latencyMs) || latencyMs < 0) return;
        this.data.stops += 1;
        this.data.stopLatencyMs.push(Math.round(latencyMs));
        this.data.stopLatencyMs = this.data.stopLatencyMs.slice(-100);
        this.persist();
    }

    recordStage(stage: string | null) {
        if (!stage || stage === this.data.currentStage) return;
        this.data.currentStage = stage;
        this.data.stageTransitions.push({ stage, at: Date.now() });
        this.data.stageTransitions = this.data.stageTransitions.slice(-20);
        this.persist();
    }

    snapshot() {
        return JSON.parse(JSON.stringify(this.data));
    }

    private persist() {
        try { writeFileSync(this.filePath, JSON.stringify(this.data, null, 2), 'utf8'); }
        catch (error: any) { console.warn('[task-metrics] failed to persist:', error?.message || error); }
    }
}
