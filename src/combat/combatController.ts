export type CombatState = 'IDLE' | 'SELECT_TARGET' | 'APPROACH' | 'FACE_TARGET' | 'ATTACK_WINDOW' | 'USE_SKILL' | 'VERIFY_RESULT' | 'RETREAT' | 'RECOVER';

export interface CombatEntity {
    entityId: number;
    type: 'hostile' | 'neutral' | 'player' | 'unknown';
    name: string;
    distance: number;
    threat?: number;
    attackingPlayer?: boolean;
    attackingSelf?: boolean;
}

export interface CombatSnapshot {
    health: number;
    food: number;
    hostileCount: number;
    combatMode?: 'vanilla' | 'epicfight';
    nearbyEntities: CombatEntity[];
    protectedPlayer?: string | null;
    playerUnderAttack?: boolean;
    now?: number;
}

export interface CombatAction {
    kind: 'select_target' | 'move' | 'look' | 'attack' | 'use_skill' | 'stop_all' | 'resume_task';
    targetEntityId?: number;
    mode?: 'tap' | 'hold';
    reason: string;
}

export interface CombatControllerOptions {
    maxChaseDistance?: number;
    maxCombatMs?: number;
    retreatHealth?: number;
    retreatFood?: number;
    maxFailures?: number;
}

const DEFAULTS = { maxChaseDistance: 32, maxCombatMs: 45_000, retreatHealth: 8, retreatFood: 6, maxFailures: 3 };

export function selectCombatTarget(snapshot: CombatSnapshot, maxDistance = DEFAULTS.maxChaseDistance): CombatEntity | null {
    const candidates = (snapshot.nearbyEntities || []).filter(entity =>
        entity.type === 'hostile' && Number.isFinite(entity.entityId) && Number.isFinite(entity.distance) && entity.distance <= maxDistance
    );
    candidates.sort((a, b) => {
        const priority = (entity: CombatEntity) => (entity.attackingPlayer ? 1000 : 0) + (entity.attackingSelf ? 500 : 0) + (entity.threat || 0) * 10 - entity.distance;
        return priority(b) - priority(a) || a.entityId - b.entityId;
    });
    return candidates[0] || null;
}

export class CombatController {
    readonly options: Required<CombatControllerOptions>;
    private state: CombatState = 'IDLE';
    private target: CombatEntity | null = null;
    private taskContext: unknown = null;
    private startedAt = 0;
    private failures = 0;

    constructor(options: CombatControllerOptions = {}) {
        this.options = { ...DEFAULTS, ...options };
    }

    getState(): CombatState { return this.state; }
    getTarget(): CombatEntity | null { return this.target ? { ...this.target } : null; }
    getTaskContext(): unknown { return this.taskContext; }

    tick(snapshot: CombatSnapshot, taskContext: unknown = null): CombatAction | null {
        const now = snapshot.now ?? Date.now();
        if (this.state === 'IDLE') {
            const target = selectCombatTarget(snapshot, this.options.maxChaseDistance);
            if (!target) return null;
            this.target = target;
            this.taskContext = taskContext;
            this.startedAt = now;
            this.failures = 0;
            this.state = 'SELECT_TARGET';
            return { kind: 'select_target', targetEntityId: target.entityId, reason: 'hostile threat detected' };
        }

        // RETREAT is an explicit terminal phase. Handle it before target lookup so
        // retreat still works when the hostile disappeared from the latest snapshot.
        if (this.state === 'RETREAT') {
            this.state = 'RECOVER';
            return { kind: 'stop_all', reason: 'stop combat before recovery' };
        }
        if (this.state === 'RECOVER') {
            // The resume action was emitted when entering RECOVER. Clear internal
            // state on the following tick without emitting a duplicate command.
            this.reset();
            return null;
        }

        if (snapshot.health <= this.options.retreatHealth || snapshot.food <= this.options.retreatFood || now - this.startedAt > this.options.maxCombatMs || this.failures >= this.options.maxFailures) {
            this.state = 'RETREAT';
            return { kind: 'move', targetEntityId: this.target?.entityId, reason: 'safety threshold or combat budget reached' };
        }

        const target = this.target && snapshot.nearbyEntities.find(entity => entity.entityId === this.target?.entityId && entity.type === 'hostile');
        if (!target) {
            this.target = null;
            this.state = 'RECOVER';
            return { kind: 'resume_task', reason: 'target lost; resume previous task' };
        }
        this.target = target;
        if (this.state === 'SELECT_TARGET') { this.state = 'APPROACH'; return { kind: 'move', targetEntityId: target.entityId, reason: 'approach selected target' }; }
        if (this.state === 'APPROACH') {
            if (target.distance > 3.5) return { kind: 'move', targetEntityId: target.entityId, reason: 'target outside attack range' };
            this.state = 'FACE_TARGET';
            return { kind: 'look', targetEntityId: target.entityId, reason: 'face target before attack' };
        }
        if (this.state === 'FACE_TARGET') { this.state = 'ATTACK_WINDOW'; return { kind: 'attack', targetEntityId: target.entityId, mode: 'tap', reason: 'attack target' }; }
        if (this.state === 'ATTACK_WINDOW') { this.state = snapshot.combatMode === 'epicfight' ? 'USE_SKILL' : 'VERIFY_RESULT'; return snapshot.combatMode === 'epicfight' ? { kind: 'use_skill', targetEntityId: target.entityId, reason: 'use configured Epic Fight skill' } : { kind: 'look', targetEntityId: target.entityId, reason: 'verify target state' }; }
        if (this.state === 'USE_SKILL') { this.state = 'VERIFY_RESULT'; return { kind: 'look', targetEntityId: target.entityId, reason: 'verify skill result' }; }
        if (this.state === 'VERIFY_RESULT') { this.state = 'APPROACH'; return { kind: 'move', targetEntityId: target.entityId, reason: 'continue combat loop' }; }
        return null;
    }

    recordFailure(): void { this.failures += 1; }
    requestRetreat(): void {
        if (this.state !== 'IDLE' && this.state !== 'RECOVER') this.state = 'RETREAT';
    }
    reset(): void { this.state = 'IDLE'; this.target = null; this.taskContext = null; this.startedAt = 0; this.failures = 0; }
}
