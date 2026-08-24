# Mineflayer State Machine Migration Design

## Status

Design approved for implementation planning. This phase defines the migration
boundary; runtime code and dependency changes begin only after this document is
accepted.

## Decision

Adopt [`mineflayer-statemachine`](https://github.com/TheDudeFromCI/mineflayer-statemachine)
as a narrow execution-state layer for MineAI. Keep DeepSeek, the Chinese
voice pipeline, `SelfPrompter`, `AutonomyController`, the existing command
permission policy, and the Forge bridge as separate owners during the first
phase.

The plugin is MIT licensed, TypeScript-based, currently published as `1.7.0`,
and targets Mineflayer behavior composition. It must be pinned to an exact
version and tested against this project's Minecraft/Node versions before
activation.

## Problem Being Solved

MineAI currently coordinates long-running behavior through several independent
mechanisms: `SelfPrompter`, `AutonomyController`, `ActionManager`, Pathfinder,
death recovery, and `bot.interrupt_code`. These mechanisms are useful but can
race when a player interrupts a task, a path fails, the bot dies, or Forge and
Mineflayer disagree about the active action.

The state machine will make execution ownership explicit and provide one
transition point for cancellation, failure, recovery, and completion.

## Target State Model

```text
IDLE
  -> PLANNING
  -> EXECUTING
  -> VERIFYING
  -> COMPLETED -> IDLE

PLANNING / EXECUTING / VERIFYING
  -> PAUSED_FOR_PLAYER
  -> RECOVERING
  -> STOPPING

RECOVERING -> PLANNING | PAUSED_FOR_PLAYER | STOPPING
STOPPING -> IDLE
```

State meanings:

- `IDLE`: no owned action may write movement, inventory, or combat state.
- `PLANNING`: accepts a high-level goal and selects one bounded next action.
- `EXECUTING`: owns one Mineflayer/Forge action and its cancellation signal.
- `VERIFYING`: checks the postcondition from a fresh snapshot; no success is
  inferred from a resolved promise alone.
- `PAUSED_FOR_PLAYER`: waiting for a player confirmation, new instruction, or
  explicit resume; actions are stopped.
- `RECOVERING`: handles death, disconnect, path failure, stale target, or
  inventory/resource mismatch with bounded retries.
- `STOPPING`: idempotently interrupts Mineflayer and requests Forge stop when
  configured, then returns to `IDLE`.

## Existing Module Mapping

| Existing owner | First-phase relationship |
|---|---|
| `SelfPrompter` | Remains the goal/request scheduler; emits state-machine intents instead of directly owning action timing where possible |
| `AutonomyController` | Remains high-level stage selection; submits one next action to the state machine |
| `ActionManager` | Remains timeout and action outcome authority; state machine consumes its structured result |
| `skills.ts` | Remains the execution library; no bulk rewrite |
| `mineflayer-pathfinder` | Remains movement backend used by states |
| `deathRecovery.ts` | Becomes a recovery transition source |
| `commandPermissionPolicy.ts` | Remains the only permission/confirmation authority |
| `mindserver.ts` / Forge bridge | Remains the only Forge transport and ACK authority |
| `voiceRuntime.ts` | Sends stop/new-goal interrupts; does not manipulate state-machine internals |

The first implementation must not replace `SelfPrompter` or introduce a second
permission system. It should wrap existing actions and expose state transitions
through a small adapter owned by `src/agent/execution/`.

## First-Phase States to Implement

Implement only these states initially:

1. `IdleState`
2. `ExecutingActionState`
3. `VerifyingActionState`
4. `StoppingState`
5. `RecoveringState`

`PlanningState` remains a thin adapter around the current autonomy/command
planner until execution ownership is proven. `PausedForPlayerState` can map to
the existing `SelfPrompter` waiting/paused states initially.

Initial action coverage:

- movement/follow;
- bounded block collection;
- safe stop;
- one existing Forge `stop_all` operation.

Crafting, armor transfer, combat modes, generated code, and autonomous
multi-action goals remain outside the first migration until postcondition and
rollback tests exist.

## Transition Contract

Each submitted action must include:

- `actionId` and originating player/agent identity;
- action kind and validated arguments;
- a cancellation signal;
- timeout and retry budget;
- expected postcondition;
- whether Forge involvement is allowed;
- audit metadata.

The state machine may transition to `EXECUTING` only after the existing policy
accepts the action. A successful executor result transitions to `VERIFYING`,
which requires a fresh state read. A failed, timed-out, or interrupted action
transitions to `RECOVERING` or `STOPPING`; it never silently returns to
`IDLE` as success.

## Stop and Interrupt Semantics

`!stop`, voice stop, player interruption, process shutdown, and Forge stop must
all be idempotent:

1. mark the active action cancelled;
2. set the existing `bot.interrupt_code` compatibility flag;
3. stop Pathfinder, collect-block, PVP, and current Mineflayer actions;
4. request Forge `stop_all` through the authenticated bridge when enabled;
5. wait for the existing ACK/timeout result;
6. clear owned state and enter `IDLE`.

An old action completion must be ignored after its `actionId` is cancelled or
replaced. This is the main correctness property the migration is intended to
add.

## Recovery Policy

Recovery is bounded and typed:

- path failure: refresh snapshot, retry with a new target once;
- stale target: invalidate target and return to planning;
- death/disconnect: use existing death recovery, then pause if the world is
  not ready;
- timeout: stop current action, record timeout, then pause or retry according
  to action policy;
- inventory/full or permission denial: report the domain outcome and do not
  retry automatically.

## Verification and Tests

Before enabling it for the live Chinese NPC, add tests for:

- one-action-at-a-time ownership;
- successful action requires postcondition verification;
- stop cancels an in-flight action and ignores late completion;
- duplicate stop is harmless;
- timeout and path failure enter bounded recovery;
- stale Forge ACK cannot complete a replacement action;
- player confirmation remains bound to the same actor;
- state-machine disabled mode preserves the current execution path.

Run the existing focused voice, safety, Forge, process, and autonomy suites,
then run a real Forge 1.20.1 smoke test with movement, follow, stop, death,
and reconnect scenarios.

## Rollback and Feature Flag

Add a settings flag defaulting to disabled, for example:

```json
{
  "execution": {
    "state_machine": {
      "enabled": false,
      "package_version": "1.7.0"
    }
  }
}
```

When disabled, no state-machine plugin is loaded and existing behavior is
unchanged. When enabled, a runtime kill switch must return the agent to the
legacy path only after stopping active actions and clearing state. No package
upgrade or live deployment is allowed until the review checklist passes.

## Review Gate

Before implementation, confirm the exact pinned dependency, Node/Mineflayer
compatibility, and whether the plugin's state-machine lifecycle can be
disposed without leaving Pathfinder or Forge actions running. After coding,
perform a full code review and self-repair before committing or deploying.
