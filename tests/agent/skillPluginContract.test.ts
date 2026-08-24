import test from 'node:test';
import assert from 'node:assert/strict';
import { SkillPluginRegistry, validateSkillPluginManifest } from '../../src/agent/execution/skillPluginContract.js';

const base = {
    id: 'minecraft.observe', version: '1.0.0', kind: 'readonly', permissions: ['world.read'], risk: 'none',
    input: { maxPayloadBytes: 4096, timeoutMs: 3000 }, postconditions: ['result.schema.valid'], stopBehavior: 'cooperative'
};

test('normalizes valid manifests and never enables by default', () => {
    const manifest = validateSkillPluginManifest({ ...base, enabledByDefault: true });
    assert.equal(manifest.enabledByDefault, false);
    assert.equal(Object.isFrozen(manifest), true);
});

test('rejects invalid identity, risk permissions and bounded input', () => {
    assert.throws(() => validateSkillPluginManifest({ ...base, id: 'Bad ID' }), /id is invalid/);
    assert.throws(() => validateSkillPluginManifest({ ...base, risk: 'high' }), /player.confirm/);
    assert.throws(() => validateSkillPluginManifest({ ...base, kind: 'forge' }), /forge.bridge/);
    assert.throws(() => validateSkillPluginManifest({ ...base, input: { maxPayloadBytes: 1, timeoutMs: 999999 } }), /timeoutMs/);
});

test('registry rejects duplicates, filters kinds and isolates snapshots', () => {
    const registry = new SkillPluginRegistry();
    registry.register(base);
    assert.throws(() => registry.register(base), /already registered/);
    registry.register({ ...base, id: 'minecraft.follow', kind: 'mineflayer' });
    assert.equal(registry.list('mineflayer').length, 1);
    const snapshot = registry.snapshot() as any;
    snapshot[0].permissions.push('mutated');
    assert.deepEqual(registry.get('minecraft.observe')?.permissions, ['world.read']);
});
