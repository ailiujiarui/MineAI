import { BotStateMachine, NestedStateMachine, StateTransition } from 'mineflayer-statemachine';

export type ExecutionState = 'IDLE' | 'MOVING' | 'FOLLOWING' | 'COLLECTING' | 'EVADE' | 'VERIFYING' | 'STOPPING' | 'RECOVERING';

export interface ExecutionAction<T = unknown> {
    actionId: string;
    kind: string;
    run: (signal: AbortSignal) => Promise<T>;
    verify?: (result: T) => Promise<boolean> | boolean;
}

export interface StateMachineAdapterOptions {
    enabled?: boolean;
    requestInterrupt?: () => void;
}

/** Narrow, feature-flagged execution owner. Legacy ActionManager remains untouched when disabled. */
export class StateMachineExecutionAdapter {
    readonly enabled: boolean;
    private state: ExecutionState = 'IDLE';
    private active?: { action: ExecutionAction; controller: AbortController; resolve: (value: unknown) => void; reject: (reason?: unknown) => void };
    private machine?: BotStateMachine;
    private readonly transitions: StateTransition[] = [];
    private readonly requestInterrupt?: () => void;

    constructor(bot: any, options: StateMachineAdapterOptions = {}) {
        this.enabled = options.enabled === true;
        this.requestInterrupt = options.requestInterrupt;
        if (!this.enabled) return;

        const idle = this.stateNode('IDLE');
        const moving = this.stateNode('MOVING');
        const following = this.stateNode('FOLLOWING');
        const collecting = this.stateNode('COLLECTING');
        const evade = this.stateNode('EVADE');
        const verifying = this.stateNode('VERIFYING');
        const stopping = this.stateNode('STOPPING');
        const recovering = this.stateNode('RECOVERING');
        this.transitions.push(
            new StateTransition({ parent: idle, child: moving, name: 'start_moving' }),
            new StateTransition({ parent: idle, child: following, name: 'start_following' }),
            new StateTransition({ parent: idle, child: collecting, name: 'start_collecting' }),
            new StateTransition({ parent: idle, child: evade, name: 'start_evade' }),
            new StateTransition({ parent: idle, child: recovering, name: 'start_recovering' }),
            ...[moving, following, collecting, evade].flatMap(active => [
                new StateTransition({ parent: active, child: verifying, name: `complete_${active.stateName.toLowerCase()}` }),
                new StateTransition({ parent: active, child: recovering, name: `failure_${active.stateName.toLowerCase()}` }),
                new StateTransition({ parent: active, child: stopping, name: `stop_${active.stateName.toLowerCase()}` })
            ]),
            new StateTransition({ parent: verifying, child: recovering, name: 'failure_verify' }),
            new StateTransition({ parent: recovering, child: idle, name: 'recovered' }),
            new StateTransition({ parent: verifying, child: idle, name: 'verified' }),
            new StateTransition({ parent: stopping, child: idle, name: 'stopped' }),
        );
        const root = new NestedStateMachine(this.transitions, idle);
        this.machine = new BotStateMachine(bot, root);
    }

    private stateNode(name: ExecutionState): any {
        return {
            stateName: name,
            active: false,
            onStateEntered: () => { this.state = name; },
        };
    }

    getState(): ExecutionState { return this.state; }

    getSnapshot() {
        return {
            state: this.state,
            activeActionId: this.active?.action.actionId || null,
            activeKind: this.active?.action.kind || null
        };
    }

    submit<T>(action: ExecutionAction<T>): Promise<T> {
        if (!this.enabled) return action.run(new AbortController().signal);
        if (this.active) return Promise.reject(new Error('An execution action is already active'));
        const controller = new AbortController();
        return new Promise<T>((resolve, reject) => {
            this.active = { action, controller, resolve: resolve as (value: unknown) => void, reject };
            const actionState = this.stateForKind(action.kind);
            this.transition(`start_${actionState.toLowerCase()}`);
            action.run(controller.signal).then(async result => {
                if (!this.active || this.active.action.actionId !== action.actionId || controller.signal.aborted) return;
                this.transition(`complete_${actionState.toLowerCase()}`);
                const verified = action.verify ? await action.verify(result) : true;
                if (!this.active || controller.signal.aborted) return;
                if (!verified) { this.transition('failure_verify'); this.active.reject(new Error('Action postcondition failed')); this.active = undefined; this.transition('recovered'); return; }
                this.transition('verified');
                this.active.resolve(result);
                this.active = undefined;
            }).catch(error => {
                if (!this.active || this.active.action.actionId !== action.actionId || controller.signal.aborted) return;
                this.transition(`failure_${actionState.toLowerCase()}`);
                this.active.reject(error);
                this.active = undefined;
                this.transition('recovered');
            });
        });
    }

    async stop(): Promise<void> {
        if (!this.enabled || !this.active) return;
        const current = this.active;
        this.active = undefined;
        current.controller.abort();
        this.requestInterrupt?.();
        this.transition(`stop_${this.state.toLowerCase()}`);
        current.reject(new Error('Action stopped'));
        this.transition('stopped');
    }

    async recover<T>(recovery: () => Promise<T>): Promise<T> {
        if (!this.enabled) return recovery();
        await this.stop();
        this.transition('start_recovering');
        try {
            return await recovery();
        } finally {
            this.transition('recovered');
        }
    }

    private transition(name: string): void {
        const transition = this.transitions.find(item => item.name === name);
        if (!transition) return;
        transition.trigger();
        const bot = this.machine?.bot as any;
        bot?.emit?.('physicTick');
    }

    private stateForKind(kind: string): 'MOVING' | 'FOLLOWING' | 'COLLECTING' | 'EVADE' {
        const normalized = String(kind).toLowerCase();
        if (normalized.includes('follow')) return 'FOLLOWING';
        if (normalized.includes('collect')) return 'COLLECTING';
        if (normalized.includes('moveaway') || normalized.includes('evade') || normalized.includes('retreat')) return 'EVADE';
        return 'MOVING';
    }
}
