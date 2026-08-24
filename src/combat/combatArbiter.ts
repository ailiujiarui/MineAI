import { CombatController, type CombatSnapshot, type CombatAction } from './combatController.js';

export type CombatOwner = 'none' | 'autonomy' | 'voice' | 'player' | 'system';

export class CombatArbiter {
    readonly controller: CombatController;
    private owner: CombatOwner = 'none';
    private protectedPlayer: string | null = null;
    private savedTask: unknown = null;

    constructor(controller = new CombatController()) { this.controller = controller; }
    getOwner(): CombatOwner { return this.owner; }
    getProtectedPlayer(): string | null { return this.protectedPlayer; }
    setProtectedPlayer(player: string | null): void { this.protectedPlayer = player?.trim() || null; }

    request(owner: Exclude<CombatOwner, 'none'>, taskContext?: unknown): boolean {
        const priority: Record<CombatOwner, number> = { none: 0, autonomy: 1, voice: 2, player: 3, system: 4 };
        if (owner === 'system' || priority[owner] >= priority[this.owner] || this.owner === owner) {
            if (this.owner === 'none') this.savedTask = taskContext ?? null;
            this.owner = owner;
            return true;
        }
        return false;
    }

    tick(snapshot: CombatSnapshot, taskContext?: unknown): CombatAction | null {
        if (this.owner === 'none') {
            const threat = (snapshot.nearbyEntities || []).some(entity => entity.type === 'hostile');
            if (!threat) return null;
            this.request('autonomy', taskContext);
        }
        const enriched = { ...snapshot, protectedPlayer: this.protectedPlayer };
        return this.controller.tick(enriched, this.savedTask);
    }

    stop(reason = 'combat stopped'): CombatAction {
        this.controller.reset();
        this.owner = 'none';
        const action: CombatAction = { kind: 'stop_all', reason };
        this.savedTask = null;
        return action;
    }

    retreat(): CombatAction {
        if (this.controller.getState() === 'IDLE') {
            return { kind: 'stop_all', reason: 'retreat requested before combat started' };
        }
        this.controller.requestRetreat();
        return { kind: 'move', reason: 'retreat requested' };
    }

    resume(): unknown {
        const task = this.savedTask;
        this.controller.reset();
        this.owner = 'none';
        this.savedTask = null;
        return task;
    }
}
