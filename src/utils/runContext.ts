// @ts-nocheck
import { mkdirSync } from 'fs';
import path from 'path';

const state = {
    rootDir: null,
    runId: null
};

function buildRunId() {
    return new Date().toISOString().replace(/[:.]/g, '-');
}

export function initRunContext(baseDir = './runs') {
    if (state.rootDir) {
        return state.rootDir;
    }

    state.runId = buildRunId();
    state.rootDir = path.resolve(baseDir, state.runId);
    mkdirSync(state.rootDir, { recursive: true });
    mkdirSync(path.join(state.rootDir, 'bots'), { recursive: true });
    return state.rootDir;
}

export function getRunRoot() {
    return state.rootDir || initRunContext();
}

export function getRunId() {
    return state.runId || path.basename(getRunRoot());
}

export function resolveRunPath(...parts) {
    return path.join(getRunRoot(), ...parts);
}
