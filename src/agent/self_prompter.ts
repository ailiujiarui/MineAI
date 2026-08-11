// @ts-nocheck
import settings from './settings.js'
import { buildGoalPauseMessage, buildNoCommandStopMessage } from '../locale/chinese.js'

const STOPPED = 0
const ACTIVE = 1
const PAUSED = 2
const WAITING_PLAYER = 3

const FAILURE_OUTCOMES = new Set(['failed', 'denied', 'invalid', 'interrupted'])
const WAIT_OUTCOMES = new Set(['confirmation-required', 'player-action-required'])
const MAX_IDENTICAL_FAILURES = 2
const MAX_TOTAL_FAILURES = 5

function normalizeFailurePart(value) {
    if (typeof value === 'string') return value.trim().replace(/\s+/g, ' ').toLowerCase()
    return JSON.stringify(value)
}

export class SelfPrompter {
    constructor(agent) {
        this.agent = agent;
        this.state = STOPPED;
        this.loop_active = false;
        this.interrupt = false;
        this.prompt = '';
        this.idle_time = 0;
        this.cooldown = 2000;
        this.total_failures = 0;
        this.last_failure_fingerprint = '';
        this.identical_failure_count = 0;
        this.last_failed_command = '';
        this.pause_reason = '';
        this.waiting_player = null;
    }

    start(prompt) {
        console.log('Self-prompting started.');
        if (!prompt) {
            if (!this.prompt)
                return 'No prompt specified. Ignoring request.';
            prompt = this.prompt;
        }
        if (prompt !== this.prompt) this.resetFailureBudget();
        this.state = ACTIVE;
        this.pause_reason = '';
        this.waiting_player = null;
        this.prompt = prompt;
        this.startLoop();
    }

    isActive() {
        return this.state === ACTIVE;
    }

    isStopped() {
        return this.state === STOPPED;
    }

    isPaused() {
        return this.state === PAUSED;
    }

    isWaitingForPlayer() {
        return this.state === WAITING_PLAYER;
    }

    async handleLoad(prompt, state) {
        if (state == undefined)
            state = STOPPED;
        this.state = state;
        this.prompt = prompt;
        if (state !== STOPPED && !prompt)
            throw new Error('No prompt loaded when self-prompting is active');
        if (state === ACTIVE) {
            await this.start(prompt);
        }
    }

    setPromptPaused(prompt) {
        this.resetFailureBudget();
        this.prompt = prompt;
        this.state = PAUSED;
        this.pause_reason = 'manual';
    }

    resetFailureBudget() {
        this.total_failures = 0;
        this.last_failure_fingerprint = '';
        this.identical_failure_count = 0;
        this.last_failed_command = '';
    }

    handlePlayerInstruction(actor = null) {
        this.resetFailureBudget();
        const normalizedActor = String(actor || '').trim().toLowerCase();
        const canResolveWait = this.state !== WAITING_PLAYER
            || !this.waiting_player
            || normalizedActor === this.waiting_player;
        if ((this.state === WAITING_PLAYER && canResolveWait)
            || (this.state === PAUSED && this.pause_reason === 'failure-budget')) {
            this.state = ACTIVE;
            this.pause_reason = '';
            if (!this.loop_active) this.interrupt = false;
            this.waiting_player = null;
        }
    }

    waitForPlayer(actor = null) {
        this.state = WAITING_PLAYER;
        this.pause_reason = 'player-action';
        this.interrupt = true;
        this.waiting_player = String(actor || '').trim().toLowerCase() || null;
    }

    recordCommandOutcome(commandOutcome) {
        const outcome = commandOutcome?.outcome;
        if (outcome === 'success') {
            this.resetFailureBudget();
            return { paused: false };
        }
        if (WAIT_OUTCOMES.has(outcome)) {
            this.waitForPlayer(outcome === 'confirmation-required' ? commandOutcome.actor : null);
            return { paused: true, waitingForPlayer: true };
        }
        if (!FAILURE_OUTCOMES.has(outcome)) return { paused: false };

        const commandName = commandOutcome.commandName || 'unknown';
        const fingerprint = [
            normalizeFailurePart(commandName),
            normalizeFailurePart(commandOutcome.args || []),
            outcome
        ].join('|');
        this.total_failures++;
        this.identical_failure_count = fingerprint === this.last_failure_fingerprint
            ? this.identical_failure_count + 1
            : 1;
        this.last_failure_fingerprint = fingerprint;
        this.last_failed_command = commandName;

        if (this.identical_failure_count >= MAX_IDENTICAL_FAILURES || this.total_failures >= MAX_TOTAL_FAILURES) {
            this.state = PAUSED;
            this.pause_reason = 'failure-budget';
            this.interrupt = true;
            const message = buildGoalPauseMessage(
                commandName,
                this.identical_failure_count >= MAX_IDENTICAL_FAILURES,
                this.agent.prompter?.profile,
                settings.language
            );
            Promise.resolve(this.agent.openChat(message)).catch(error => {
                console.error('Failed to report self-prompt pause:', error);
            });
            return { paused: true, waitingForPlayer: false, message };
        }
        return { paused: false };
    }

    async startLoop() {
        if (this.loop_active) {
            console.warn('Self-prompt loop is already active. Ignoring request.');
            return;
        }
        console.log('starting self-prompt loop')
        this.loop_active = true;
        let no_command_count = 0;
        const MAX_NO_COMMAND = 3;
        while (!this.interrupt) {
            const msg = `You are self-prompting with the goal: '${this.prompt}'. Your next response MUST contain a command with this syntax: !commandName. Respond:`;
            
            let used_command = await this.agent.handleMessage('system', msg, 1);
            if (!used_command) {
                no_command_count++;
                if (no_command_count >= MAX_NO_COMMAND) {
                    let out = buildNoCommandStopMessage(
                        MAX_NO_COMMAND,
                        this.agent.prompter?.profile,
                        settings.language
                    );
                    this.agent.openChat(out);
                    console.warn(out);
                    this.state = STOPPED;
                    break;
                }
            }
            else {
                no_command_count = 0;
                if (this.interrupt) break;
                await new Promise(r => setTimeout(r, this.cooldown));
            }
        }
        console.log('self prompt loop stopped')
        this.loop_active = false;
        this.interrupt = false;
    }

    update(delta) {
        // automatically restarts loop
        if (this.state === ACTIVE && !this.loop_active && !this.interrupt) {
            if (this.agent.isIdle())
                this.idle_time += delta;
            else
                this.idle_time = 0;

            if (this.idle_time >= this.cooldown) {
                console.log('Restarting self-prompting...');
                this.startLoop();
                this.idle_time = 0;
            }
        }
        else {
            this.idle_time = 0;
        }
    }

    async stopLoop() {
        // you can call this without await if you don't need to wait for it to finish
        if (this.interrupt)
            return;
        console.log('stopping self-prompt loop')
        this.interrupt = true;
        while (this.loop_active) {
            await new Promise(r => setTimeout(r, 500));
        }
        this.interrupt = false;
    }

    async stop(stop_action=true) {
        this.interrupt = true;
        if (stop_action)
            await this.agent.actions.stop();
        this.stopLoop();
        this.state = STOPPED;
        this.pause_reason = '';
        this.waiting_player = null;
        this.resetFailureBudget();
    }

    async pause() {
        this.interrupt = true;
        await this.agent.actions.stop();
        this.stopLoop();
        this.state = PAUSED;
        this.pause_reason = 'manual';
    }

    shouldInterrupt(is_self_prompt) { // to be called from handleMessage
        return is_self_prompt && (this.state === ACTIVE || this.state === PAUSED) && this.interrupt;
    }

    handleUserPromptedCmd(is_self_prompt, is_action) {
        // if a user messages and the bot responds with an action, stop the self-prompt loop
        if (!is_self_prompt && is_action) {
            this.stopLoop();
            // this stops it from responding from the handlemessage loop and the self-prompt loop at the same time
        }
    }
}
