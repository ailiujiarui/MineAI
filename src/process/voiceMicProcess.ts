// @ts-nocheck
import { spawn as defaultSpawn } from 'child_process';
import path from 'path';
import { resolveVoiceMicPythonCommand } from '../voice/micLauncherConfig.js';

const DEFAULT_STOP_TIMEOUT_MS = 5000;
const DEFAULT_RESTART_LIMIT = 5;
const DEFAULT_RESTART_BACKOFF_MS = 1000;

function normalizeCommand(command) {
    const value = String(command || '').trim();
    if (!value) return value;
    if (value.includes('\\') || value.includes('/') || value.startsWith('.')) {
        return path.resolve(process.cwd(), value);
    }
    return value;
}

export class VoiceMicProcess {
    constructor(config = {}) {
        this.agent = config.agent || '';
        this.pythonCommand = normalizeCommand(resolveVoiceMicPythonCommand(config.pythonCommand));
        this.scriptPath = config.scriptPath || 'scripts/voice-mic-listener.py';
        this.childArgs = config.childArgs || [];
        this.spawnImpl = config.spawnImpl || defaultSpawn;
        this.authTokenProvider = config.authTokenProvider || null;
        this.stopTimeoutMs = config.stopTimeoutMs ?? DEFAULT_STOP_TIMEOUT_MS;
        this.restartLimit = config.restartLimit ?? DEFAULT_RESTART_LIMIT;
        this.restartBackoffMs = config.restartBackoffMs ?? DEFAULT_RESTART_BACKOFF_MS;
        this.stableUptimeMs = config.stableUptimeMs ?? 30000;
        this.now = config.now || Date.now;
        this.setTimer = config.setTimeout || setTimeout;
        this.clearTimer = config.clearTimeout || clearTimeout;
        this.onState = config.onState || (() => {});
        this.child = null;
        this.generation = 0;
        this.desiredRunning = false;
        this.ready = false;
        this.shuttingDown = false;
        this.restartCount = 0;
        this.restartTimer = null;
        this.stopPromise = null;
    }

    setReady(ready) {
        this.ready = Boolean(ready);
        if (this.ready && this.desiredRunning) return this.start();
        if (!this.ready && this.child) return this.stop('agent-not-ready', { preserveDesired: true });
        return Promise.resolve(false);
    }

    start() {
        this.desiredRunning = true;
        this.shuttingDown = false;
        if (!this.ready || this.child || this.restartTimer) return Promise.resolve(false);
        this.#spawn();
        return Promise.resolve(true);
    }

    async stop(reason = 'stopped', options = {}) {
        if (!options.preserveDesired) this.desiredRunning = false;
        this.ready = false;
        if (this.restartTimer) {
            this.clearTimer(this.restartTimer);
            this.restartTimer = null;
        }
        if (!this.child) return false;
        if (this.stopPromise) return this.stopPromise;

        const child = this.child;
        this.stopPromise = new Promise((resolve) => {
            let settled = false;
            const finish = () => {
                if (settled) return;
                settled = true;
                this.clearTimer(timeout);
                this.stopPromise = null;
                resolve(true);
            };
            const timeout = this.setTimer(() => {
                try { child.kill(); } catch {}
                finish();
            }, this.stopTimeoutMs);
            child.once('exit', finish);
            try {
                if (child.stdin && !child.stdin.destroyed) {
                    child.stdin.write('stop\n');
                    child.stdin.end();
                } else {
                    child.kill('SIGINT');
                }
            } catch {
                try { child.kill('SIGINT'); } catch {}
            }
            this.onState({ state: 'stopping', reason, agent: this.agent });
        });
        return this.stopPromise;
    }

    async shutdown() {
        this.shuttingDown = true;
        return this.stop('shutdown');
    }

    #spawn() {
        const generation = ++this.generation;
        const startedAt = this.now();
        const args = this.childArgs.length
            ? [...this.childArgs]
            : [this.scriptPath, '--agent', this.agent];
        const child = this.spawnImpl(this.pythonCommand, args, {
            stdio: ['pipe', 'inherit', 'inherit'],
            shell: false,
            env: {
                ...process.env,
                PYTHONUNBUFFERED: process.env.PYTHONUNBUFFERED || '1',
                MINDCRAFT_AGENT_NAME: this.agent,
                MINDCRAFT_AGENT_TOKEN: this.authTokenProvider ? this.authTokenProvider() : ''
            }
        });
        this.child = child;
        this.onState({ state: 'running', agent: this.agent, generation });
        let settled = false;
        const settle = (code, signal, error = null) => {
            if (settled || this.generation !== generation) return;
            settled = true;
            this.child = null;
            if (error) this.onState({ state: 'error', agent: this.agent, error, generation });
            else this.onState({ state: 'exited', agent: this.agent, code, signal, generation });
            if (this.now() - startedAt >= this.stableUptimeMs) this.restartCount = 0;
            if (this.desiredRunning && this.ready && !this.shuttingDown && this.restartCount < this.restartLimit) {
                const delay = this.restartBackoffMs * (2 ** this.restartCount);
                this.restartCount += 1;
                this.restartTimer = this.setTimer(() => {
                    this.restartTimer = null;
                    if (this.desiredRunning && this.ready && !this.shuttingDown) this.#spawn();
                }, delay);
            } else if (this.desiredRunning && this.ready && !this.shuttingDown) {
                this.onState({ state: 'restart-exhausted', agent: this.agent, generation, restartCount: this.restartCount });
            }
        };
        child.once('error', (error) => settle(null, null, error));
        child.once('exit', (code, signal) => settle(code, signal));
    }
}

export function createVoiceMicProcess(config = {}) {
    return new VoiceMicProcess(config);
}
