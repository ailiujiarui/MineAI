export type SkillPluginKind = 'readonly' | 'mineflayer' | 'forge';
export type SkillPluginRisk = 'none' | 'reversible' | 'high';
export type SkillPluginStopBehavior = 'cooperative' | 'immediate';

export interface SkillPluginManifest {
    id: string;
    version: string;
    kind: SkillPluginKind;
    permissions: string[];
    risk: SkillPluginRisk;
    input: { maxPayloadBytes: number; timeoutMs: number };
    postconditions: string[];
    stopBehavior: SkillPluginStopBehavior;
    enabledByDefault?: boolean;
}

export interface SkillPluginExecutorContext {
    actor: string;
    origin: string;
    signal: AbortSignal;
    payload: unknown;
}

export interface SkillPluginExecutionResult {
    status: 'success' | 'denied' | 'timeout' | 'interrupted' | 'failed';
    result?: unknown;
    durationMs?: number;
    postconditionPassed?: boolean;
}

export interface SkillPluginExecutor {
    execute(context: SkillPluginExecutorContext): Promise<SkillPluginExecutionResult>;
}

const ID_PATTERN = /^[a-z0-9][a-z0-9._-]{1,63}$/;
const SEMVER_PATTERN = /^\d+\.\d+\.\d+(?:-[0-9a-z.-]+)?$/i;
const KINDS = new Set<SkillPluginKind>(['readonly', 'mineflayer', 'forge']);
const RISKS = new Set<SkillPluginRisk>(['none', 'reversible', 'high']);
const STOPS = new Set<SkillPluginStopBehavior>(['cooperative', 'immediate']);
const MAX_PAYLOAD_BYTES = 1024 * 1024;
const MAX_TIMEOUT_MS = 120_000;

function fail(message: string): never {
    throw new Error(`Invalid skill plugin manifest: ${message}`);
}

export function validateSkillPluginManifest(value: unknown): SkillPluginManifest {
    if (!value || typeof value !== 'object') fail('manifest must be an object');
    const input: any = value;
    if (typeof input.id !== 'string' || !ID_PATTERN.test(input.id)) fail('id is invalid');
    if (typeof input.version !== 'string' || !SEMVER_PATTERN.test(input.version)) fail('version is invalid');
    if (!KINDS.has(input.kind)) fail('kind is invalid');
    if (!Array.isArray(input.permissions) || input.permissions.some((item: unknown) => typeof item !== 'string' || !item.trim())) fail('permissions must be non-empty strings');
    if (!RISKS.has(input.risk)) fail('risk is invalid');
    if (input.risk === 'high' && !input.permissions.includes('player.confirm')) fail('high risk requires player.confirm');
    if (input.kind === 'forge' && !input.permissions.includes('forge.bridge')) fail('forge plugins require forge.bridge');
    if (!input.input || !Number.isInteger(input.input.maxPayloadBytes) || input.input.maxPayloadBytes < 1 || input.input.maxPayloadBytes > MAX_PAYLOAD_BYTES) fail('maxPayloadBytes is out of bounds');
    if (!Number.isInteger(input.input.timeoutMs) || input.input.timeoutMs < 1 || input.input.timeoutMs > MAX_TIMEOUT_MS) fail('timeoutMs is out of bounds');
    if (!Array.isArray(input.postconditions) || input.postconditions.length === 0 || input.postconditions.some((item: unknown) => typeof item !== 'string' || !item.trim())) fail('postconditions are required');
    if (!STOPS.has(input.stopBehavior)) fail('stopBehavior is invalid');
    return Object.freeze({
        id: input.id,
        version: input.version,
        kind: input.kind,
        permissions: Object.freeze([...input.permissions]),
        risk: input.risk,
        input: Object.freeze({ maxPayloadBytes: input.input.maxPayloadBytes, timeoutMs: input.input.timeoutMs }),
        postconditions: Object.freeze([...input.postconditions]),
        stopBehavior: input.stopBehavior,
        enabledByDefault: false
    }) as SkillPluginManifest;
}

export class SkillPluginRegistry {
    private readonly manifests = new Map<string, SkillPluginManifest>();

    register(manifest: unknown): SkillPluginManifest {
        const normalized = validateSkillPluginManifest(manifest);
        if (this.manifests.has(normalized.id)) throw new Error(`Skill plugin already registered: ${normalized.id}`);
        this.manifests.set(normalized.id, normalized);
        return normalized;
    }

    get(id: string): SkillPluginManifest | null {
        return this.manifests.get(id) || null;
    }

    list(kind?: SkillPluginKind): SkillPluginManifest[] {
        return [...this.manifests.values()].filter(item => !kind || item.kind === kind);
    }

    snapshot(): ReadonlyArray<SkillPluginManifest> {
        return Object.freeze(this.list().map(item => ({
            ...item,
            permissions: [...item.permissions],
            input: { ...item.input },
            postconditions: [...item.postconditions]
        })));
    }
}
