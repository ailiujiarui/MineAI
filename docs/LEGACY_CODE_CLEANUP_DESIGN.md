# Legacy Voice Code Cleanup Design

## Status

Approved for implementation. OpenVoice was superseded by the Doubao HTTP v3
TTS integration and is no longer part of the supported runtime.

## Scope

Remove only OpenVoice-specific assets:

- `src/voice/providers/openVoiceLocal.ts`
- `scripts/openvoice_tts.py`
- `scripts/test-openvoice-local.ts`
- `scripts/voice-profiles.ts` (OpenVoice-only profile preview utility)
- OpenVoice unit tests and the OpenVoice provider-factory branch
- `voice.openvoice` settings and `voice:test:openvoice` npm script

Keep the provider factory, Doubao ASR/TTS, microphone wrappers, and generic
voice profile methods. `!setVoiceProfile` remains available as a provider-
agnostic command; it succeeds only when the active TTS adapter exposes a
configured profile.

## Compatibility and rollback

The supported provider is `doubao`. Configurations selecting
`openvoice-local` now fall back to the existing null adapter instead of
loading an unmaintained local runtime. Reverting this cleanup commit restores
the old files if a legacy deployment still requires them.

## Validation

Run the voice-focused tests, TypeScript typecheck, and a repository-wide scan
to ensure no executable OpenVoice references remain.
