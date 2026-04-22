import test from 'node:test';
import assert from 'node:assert/strict';

import { createEmbodimentRuntime } from '../../src/embodiment/embodimentRuntime.js';

test('tracks profile identity and updates tags from autonomy, companion, and voice', () => {
  const runtime = createEmbodimentRuntime({
    profile: {
      id: 'maid-default',
      displayName: 'Maid',
      modelProvider: 'ysm',
      modelId: 'maid/default'
    }
  });

  runtime.applyAutonomySignal({ state: 'tasking', urgency: 'focused' });
  runtime.applyCompanionSignal({ mood: 'warm', expression: 'happy' });
  runtime.applyVoiceSignal({ speaking: true, style: 'gentle' });

  const state = runtime.getState();
  assert.equal(state.presence, 'speaking');
  assert.equal(state.emotion, 'happy');
  assert.equal(state.action, 'tasking');
  assert.equal(state.voiceStyle, 'gentle');
});

test('can swap profiles and clear transient speaking state', () => {
  const runtime = createEmbodimentRuntime({
    profile: {
      id: 'default',
      displayName: 'Default',
      modelProvider: 'ysm',
      modelId: 'default/model'
    }
  });

  runtime.applyVoiceSignal({ speaking: true, style: 'neutral' });
  runtime.setProfile({
    id: 'combat',
    displayName: 'Combat',
    modelProvider: 'ysm',
    modelId: 'combat/model'
  });
  runtime.clearTransientSignals();

  const state = runtime.getState();
  assert.equal(state.profile.id, 'combat');
  assert.equal(state.presence, 'idle');
});
