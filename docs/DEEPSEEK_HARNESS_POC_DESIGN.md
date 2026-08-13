# DeepSeek Harness Minecraft NPC PoC Design

## Status

Design approved for documentation only. No Harness dependency or runtime code
is added in this phase.

## Decision

DeepSeek Harness (`dsh`) will be evaluated as a sidecar reasoning runtime. The
existing MineAI Agent remains the production path until the PoC meets the
acceptance criteria below.

The boundary is:

```text
Minecraft chat / Doubao ASR
        -> MineAI adapter
        -> Harness Agent
        -> structured Minecraft tool request
        -> existing MineAI permission and execution layers
        -> Mineflayer / Forge bridge
        -> structured result
        -> Harness reply
        -> MineAI chat / Doubao HTTP v3 TTS
```

Harness must not directly control Mineflayer, Forge, the microphone, TTS, or
the filesystem. It receives a narrow tool surface supplied by a MineAI plugin
or adapter.

## Why Sidecar First

Harness is a developer-preview framework with a plugin-based Cordis runtime,
durable session events, native tool schemas, an agent loop, and approval and
sandbox extension points. It is substantially broader than the current
OpenAI-compatible DeepSeek client, so a direct replacement would combine a
model migration with a control-plane migration and make failures difficult to
localize.

The sidecar keeps the current Chinese NPC, voice path, safety policy, and Forge
bridge available for rollback while measuring whether native structured tools
improve Minecraft task execution.

## Phase 1 Scope

The first PoC exposes only read-only or immediately reversible capabilities:

- `minecraft_observe`: bounded player, bot, position, health, game mode,
  inventory summary, nearby entities, and current task state.
- `minecraft_get_block`: inspect one bounded coordinate or the targeted block.
- `minecraft_get_capabilities`: report Mineflayer/Forge capabilities and the
  configured safety mode.
- `minecraft_stop`: invoke the existing stop path only; it remains subject to
  Agent identity, runtime readiness, and Forge ACK rules.

No mining, crafting, item transfer, arbitrary code, shell, or combat tool is
enabled in Phase 1.

## Tool Contract

Each Harness tool must have:

- a strict parameter schema with bounded strings, coordinates, counts, and
  timeouts;
- a canonical JSON result, separate from human-readable rendering;
- an execution signal and timeout;
- the authenticated MineAI Agent identity and player actor context;
- an audit record containing request id, tool name, actor, policy result,
  execution result, and duration;
- fail-closed behavior when the Agent, Forge bridge, or snapshot is stale.

Harness tools call existing TypeScript functions and channels. They must not
reimplement command parsing or permission decisions. High-risk confirmation,
player binding, rate limits, and Forge stop ACK behavior remain owned by
`src/safety/`, `src/agent/commands/`, and `src/clientBridge/`.

## Prompt and Language

The Harness profile receives the Chinese NPC identity, Minecraft terminology,
current task, structured memory summary, and the same response constraints as
the existing profile. It must:

- answer in Chinese by default;
- explain an intended action before requesting a tool;
- request one tool action at a time in Phase 1;
- never fabricate a successful result;
- distinguish unavailable, denied, timed out, and completed outcomes.

Doubao remains speech-only: duplex realtime is ASR and HTTP v3 TTS speaks the
Harness/MineAI reply. Harness native output audio is not enabled.

## Adapter Shape

The adapter is an explicit boundary, not a fork of MineAI's Agent internals.
It will provide:

- `startSession(context)` and `stopSession(reason)`;
- `submitUserMessage(message, metadata)`;
- `observe()` for bounded state snapshots;
- `executeTool(call, actor)` through existing MineAI policy;
- `onAssistantMessage(message)` for chat and TTS routing;
- cancellation propagation from player interruption and `!stop`.

The adapter must support a feature flag and a per-session kill switch. If the
Harness process, API, or adapter fails, MineAI falls back to the existing
DeepSeek client without changing credentials or voice configuration.

## Phase 1 Acceptance

The PoC is considered viable only if it passes all of these against the same
Minecraft test world:

1. At least 30 Chinese observe/stop tasks with no unauthorized tool calls.
2. Zero fabricated success results in recorded transcripts.
3. Every stop request reaches the existing local stop and Forge ACK path.
4. A stale snapshot, disconnected Forge bridge, and cancelled request fail
   closed within the configured timeout.
5. Harness can be disabled without restarting the microphone or changing the
   DeepSeek `.env` configuration.
6. Median response latency and token usage are no worse than the current path
   by more than 20 percent for equivalent prompts.
7. Existing MineAI voice, safety, Forge, and full focused test suites remain
   green.

## Phase 2 Gate

Only after Phase 1 passes may reversible action tools be considered:

- `minecraft_follow_player`;
- bounded movement;
- bounded item lookup;
- bounded collection with explicit cancellation.

Crafting, item transfer, combat, arbitrary code, shell, and autonomous
long-running goals require a separate design and approval.

## Risks and Rollback

- Harness developer-preview compatibility changes: pin an exact version and
  keep the adapter isolated.
- Duplicate memory/session state: Harness session events are not authoritative
  until reconciliation with MineAI history is designed.
- Tool-policy drift: all actions continue through MineAI policy and audit code.
- Double replies: only one runtime owns user-facing response emission per
  session.
- Network/provider failure: disable the feature flag and route to the current
  DeepSeek implementation.

## Required Implementation Review

Before code is written, confirm the exact Harness package/version and whether
the project will run it as a local headless process or embed its packages.
After implementation, review adapter lifecycle, identity propagation,
timeouts, cancellation, duplicate response suppression, secret handling, and
rollback behavior before any deployment or push.
