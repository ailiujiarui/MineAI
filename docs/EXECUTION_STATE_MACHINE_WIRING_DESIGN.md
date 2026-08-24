# Execution State Machine Wiring Design

## Scope

Wire the feature-flagged execution adapter around the existing `ActionManager`
boundary. The manager remains timeout, output, and action-result authority;
the adapter adds ownership, cancellation, and postcondition verification.

## Rules

- `execution.state_machine.enabled` remains false by default.
- Disabled mode calls the current ActionManager path byte-for-byte.
- Enabled mode submits one action at a time with a stable action id.
- Permission checks and Forge commands remain outside this wrapper.
- Stop first cancels the state-machine action, then runs the existing cleanup.

## Verification

Test enabled and disabled execution, concurrent-action rejection, late result
discard, stop idempotence, and the existing ActionManager result contract.
