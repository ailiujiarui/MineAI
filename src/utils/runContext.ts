// @ts-nocheck
import { mkdirSync } from 'fs';
import path from 'path';

const state = {
    rootDir: null,
    runId: null
};

export const RUN_ROOT_ENV = 'MINDCRAFT_RUN_ROOT';

function buildRunId() {
    return new Date().toISOString().replace(/[:.]/g, '-');
}

export function initRunContext(baseDir = './runs', inheritedRoot = process.env[RUN_ROOT_ENV]) {
    if (state.rootDir) {
        return state.rootDir;
    }

    if (inheritedRoot) {
        state.rootDir = path.resolve(inheritedRoot);
        state.runId = path.basename(state.rootDir);
    } else {
        state.runId = buildRunId();
        state.rootDir = path.resolve(baseDir, state.runId);
    }
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
