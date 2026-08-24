# Phase 3 Curriculum and Metrics Design

## Scope

Add observability around the existing autonomy curriculum. The current stage
planner remains authoritative; metrics are advisory and must never issue a
command or bypass permissions.

## Metrics

Track bounded counters per NPC run and persist a compact summary:

- action total, successes, failures;
- failure counts by stable reason;
- recovery count;
- stop requests and stop latency samples;
- current autonomy stage and stage transitions.

Keep the last 100 latency samples and at most 20 stage transitions. Do not
persist chat text, inventory contents, player identities, or credentials.

## Curriculum Boundary

Reuse existing stages (`gather_wood`, tool upgrades, furnace, iron, steady
state). Metrics only help explain progress and identify repeated failures. They
do not create a second planner or automatically skip prerequisites.

## Acceptance

- Existing autonomy command sequences remain unchanged when no metrics exist.
- Feedback updates metrics without failing an action.
- Metrics survive a process restart through the run directory.
- A bounded diagnostics snapshot is available for future DeepSeek planning.
