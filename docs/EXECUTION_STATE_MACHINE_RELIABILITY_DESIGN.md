# Execution State Machine Reliability Design

## Problem

`ActionManager` historically stops any preceding action at the start of a new
action. Once it is wrapped by the state machine, that internal cleanup must
not cancel the action that the state machine has just submitted.

## Design

- Add an internal ActionManager stop mode that preserves the state-machine
  owner during pre-action cleanup.
- Explicit player, voice, death, timeout, and shutdown stops still cancel the
  state-machine owner first.
- Treat ActionManager's structured `success: false` outcome as a verification
  failure, including timeouts and interrupted actions.
- Expose only a bounded execution snapshot (`state`, active action id/kind)
  for diagnostics; it contains no player text, credentials, or game snapshot.

## Acceptance

- An enabled ActionManager action completes without self-cancellation.
- External stop rejects the in-flight action, invokes legacy Mineflayer
  cleanup once, and returns to `IDLE`.
- A failed ActionManager result transitions through recovery and rejects.
- Disabled mode remains unchanged.
