import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolveRunPath } from '../../utils/runContext.js';

export class SkillExperienceStore {
    private readonly filePath: string;
    private records: any[] = [];

    constructor(agentName: string) {
        const root = resolveRunPath('bots', agentName);
        mkdirSync(root, { recursive: true });
        this.filePath = `${root}/skill-experiences.json`;
    }

    load() {
        try {
            if (!existsSync(this.filePath)) return [];
            const parsed = JSON.parse(readFileSync(this.filePath, 'utf8'));
            this.records = Array.isArray(parsed) ? parsed.slice(-100) : [];
        } catch (error: any) {
            console.warn('[skill-experience] failed to load:', error?.message || error);
            this.records = [];
        }
        return this.records.slice();
    }

    record(feedback: any) {
        const compact = {
            actionId: feedback.actionId,
            actionLabel: feedback.actionLabel,
            startedAt: feedback.startedAt,
            durationMs: feedback.durationMs,
            success: feedback.success === true,
            failureReason: feedback.failureReason,
            beforeState: feedback.before?.executionState || null,
            afterState: feedback.after?.executionState || null
        };
        this.records.push(compact);
        if (this.records.length > 100) this.records.splice(0, this.records.length - 100);
        try {
            writeFileSync(this.filePath, JSON.stringify(this.records, null, 2), 'utf8');
        } catch (error: any) {
            console.warn('[skill-experience] failed to persist:', error?.message || error);
        }
        return compact;
    }

    getRecent(limit = 20) {
        return this.records.slice(-Math.max(0, Math.min(100, Math.floor(limit))));
    }
}
