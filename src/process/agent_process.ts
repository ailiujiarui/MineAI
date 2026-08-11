// @ts-nocheck
import { spawn as defaultSpawn } from 'child_process';
import { logoutAgent } from '../mindcraft/mindserver.js';
import { initRunContext, RUN_ROOT_ENV } from '../utils/runContext.js';

export class AgentProcess {
    constructor(name, port, options = {}) {
        this.name = name;
        this.port = port;
        this.spawnImpl = options.spawnImpl || defaultSpawn;
        this.authToken = options.authToken || '';
        this.authTokenProvider = options.authTokenProvider || null;
        this.minRestartUptimeMs = options.minRestartUptimeMs ?? 10000;
        this.runRoot = options.runRoot || initRunContext(options.runBaseDir);
        this.running = false;
        this.desiredRunning = false;
        this.plannedRestart = false;
        this.generation = 0;
        this.process = null;
        this.startOptions = null;
    }

    start(load_memory = false, init_message = null, count_id = 0) {
        this.startOptions = { load_memory, init_message, count_id };
        this.desiredRunning = true;
        if (this.running) return false;
        this.#spawnGeneration();
        return true;
    }

    #spawnGeneration(restartCause = null) {
        const { load_memory, init_message, count_id } = this.startOptions;
        const generation = ++this.generation;
        const args = ['--import', 'tsx', 'src/process/init_agent.ts', this.name, '-n', this.name, '-c', count_id];
        if (load_memory) args.push('-l', load_memory);
        if (generation === 1 && init_message) args.push('-m', init_message);
        if (restartCause) args.push('--restart-cause', restartCause);
        args.push('-p', this.port);

        const startedAt = Date.now();
        const authToken = this.authTokenProvider ? this.authTokenProvider() : this.authToken;
        const child = this.spawnImpl(process.execPath, args, {
            stdio: 'inherit',
            env: {
                ...process.env,
                MINDCRAFT_AGENT_TOKEN: authToken,
                [RUN_ROOT_ENV]: this.runRoot
            }
        });
        this.process = child;
        this.running = true;

        child.once('exit', (code, signal) => {
            if (generation !== this.generation) return;
            this.running = false;
            this.process = null;
            logoutAgent(this.name);
            console.log(`Agent process exited with code ${code} and signal ${signal}`);

            if (!this.desiredRunning) return;
            if (this.plannedRestart) {
                this.plannedRestart = false;
                this.startOptions.load_memory = true;
                this.#spawnGeneration('planned');
                return;
            }
            if (code !== 0 && signal !== 'SIGINT') {
                if (Date.now() - startedAt < this.minRestartUptimeMs) {
                    console.error('Agent process exited too quickly and will not be restarted.');
                    this.desiredRunning = false;
                    return;
                }
                this.startOptions.load_memory = true;
                this.#spawnGeneration(`unexpected-exit:${code ?? 'unknown'}:${signal ?? 'none'}`);
            }
        });

        child.once('error', (error) => {
            if (generation !== this.generation) return;
            console.error('Agent process error:', error);
        });
    }

    stop() {
        this.desiredRunning = false;
        this.plannedRestart = false;
        if (!this.running || !this.process) return false;
        this.process.kill('SIGINT');
        return true;
    }

    waitForExit(timeoutMs = 2000) {
        if (!this.running || !this.process) return Promise.resolve(true);
        const child = this.process;
        return new Promise((resolve) => {
            let settled = false;
            const finish = (value) => {
                if (settled) return;
                settled = true;
                clearTimeout(timeout);
                child.off('exit', onExit);
                resolve(value);
            };
            const onExit = () => finish(true);
            const timeout = setTimeout(() => finish(false), timeoutMs);
            child.once('exit', onExit);
        });
    }

    forceRestart() {
        this.desiredRunning = true;
        if (!this.running || !this.process) {
            if (!this.startOptions) {
                this.startOptions = { load_memory: true, init_message: null, count_id: 0 };
            } else {
                this.startOptions.load_memory = true;
            }
            this.#spawnGeneration('planned');
            return true;
        }
        if (this.plannedRestart) return false;
        this.plannedRestart = true;
        this.process.kill('SIGINT');
        return true;
    }
}
