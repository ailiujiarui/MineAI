# Phase 2 Skill Feedback Design

## Scope

Add a small, provider-neutral contract for recording action context and
environment feedback. The first implementation wraps `ActionManager` results;
it does not change DeepSeek prompts, permissions, Forge ownership, or action
selection.

## Contract

Each action feedback record contains:

- action label and stable action id;
- bounded pre/post Mineflayer snapshot;
- success, interrupted, timed-out, or failed result;
- typed failure reason;
- duration.

Snapshots contain only safe operational fields: position, health, food,
inventory item counts, current state, and active action kind. They must not
contain credentials, full chat history, or arbitrary player data.

## Failure Classification

Use these stable reasons: `none`, `interrupted`, `timeout`, `permission_denied`,
`inventory_full`, `target_missing`, `path_failed`, `postcondition_failed`, and
`execution_error`. Unknown failures remain `execution_error` with the original
human-readable message preserved in the existing result path.

## Rollback

The feedback object is additive and optional. If snapshot collection or a
consumer callback fails, the original ActionManager result must still be
returned unchanged.
