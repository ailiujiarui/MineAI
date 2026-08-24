# Phase 3 DeepSeek Feedback Design

## Scope

Expose a bounded, Chinese-readable summary of recent skill feedback to the
existing DeepSeek prompt context. Add a small experience store for successful
skills and bounded failures. Do not change command permissions, state-machine
ownership, or provider credentials.

## Prompt Boundary

- Include at most the latest five feedback records.
- Include action label, result, failure reason, duration, and coarse state.
- Include inventory deltas only for items changed by the action; never include
  full inventory or secrets.
- Mark feedback as observed execution data, not as user instructions.
- DeepSeek may use feedback to choose the next bounded command, but never gets
  direct API, shell, Forge, or filesystem access.

## Experience Store

- Retain at most 100 records per Agent process.
- Store successful skill context and postcondition summary.
- Store failure reason for replanning and diagnostics.
- Do not persist arbitrary chat text or credentials.
- The store is advisory; it cannot authorize or execute a command.

The first persistent implementation stores bounded records in the agent run
directory at `bots/<agent>/skill-experiences.json`, alongside existing memory.

## Acceptance

- Prompt rendering contains feedback only when records exist.
- Feedback is bounded and sanitized.
- Existing prompts remain unchanged when no feedback exists.
- Experience writes cannot fail an action or a DeepSeek request.
