# Mineflayer Execution Upgrade Design

## Status

Pending approval before implementation.

## Facts From the Current Repository

- `mineflayer-statemachine@1.7.0` is installed and feature-flagged, but is
  currently a generic action wrapper rather than explicit gameplay states.
- `mineflayer-pvp@1.3.2` is already loaded by `src/utils/mcdata.ts` and used
  for Mineflayer-side combat fallback.
- `mineflayer-pathfinder@2.4.5` is the currently published Pathfinder version.
  The `4.37.1` version refers to the Mineflayer core package, not Pathfinder.
- `skills.collectBlock` already calls `bot.tool.equipForBlock(block)`, but the
  project does not directly declare or load `mineflayer-tool`. This currently
  relies on an indirect dependency and is not reproducible.

## Phase A: Explicit State Ownership

Extend the execution adapter with explicitly named action kinds and bounded
state snapshots:

| Intent | State-machine action kind | Existing executor |
| --- | --- | --- |
| Follow / move | `FOLLOWING` / `MOVING` | `skills.followPlayer`, movement skills |
| Bounded gather | `COLLECTING` | `skills.collectBlock` |
| Retreat | `EVADE` | `skills.moveAway`, combat navigator |
| Death / reconnect | `RECOVERING` | `deathRecovery.ts` |
| No active goal | `IDLE` | existing idle event |
| Player, voice, Forge, shutdown stop | `STOPPING` | existing interrupt + Forge ACK path |

This is a state-model refinement only. DeepSeek remains planner, Voyager ideas
remain local design patterns (skill documentation, environment feedback, and
postcondition checks), and no second planner is introduced.

## Phase B: Direct Tool Selection Dependency

Add and pin `mineflayer-tool@1.2.0`, explicitly load its plugin after
Pathfinder, and retain the existing `equipForBlock` call. Add a readiness
guard so collection fails clearly instead of throwing when the plugin is
unavailable. This improves tool selection without granting new actions.

## Phase C: Pathfinder Compatibility Validation

No Pathfinder version bump is proposed: `2.4.5` is current. Instead, test the
installed graph against `mineflayer@4.33.0` and Minecraft Forge 1.20.1:

- path to coordinates;
- follow and explicit stop;
- collect with tool selection;
- evade path;
- death/reconnect cleanup.

Only upgrade Mineflayer core after a separate compatibility design and test
matrix; it is not a Pathfinder upgrade.

## Phase D: PVP Boundary

Keep `mineflayer-pvp` as an opt-in Mineflayer fallback for vanilla enemies.
When the Forge bridge reports modded combat control/capability, Forge remains
the combat owner. The state machine can stop PVP but must never issue a
parallel Mineflayer attack while Forge combat owns the target.

## Acceptance and Rollback

- Explicit follow, collect, evade, recovery, idle, and stop states are
  observable with no overlapping active owner.
- Direct `mineflayer-tool` install/load is verified by a clean dependency
  graph and unit tests.
- Existing permission policy and Forge stop ACK tests remain green.
- The state-machine flag remains false by default; disabling it restores the
  legacy path without a restart.
- No Forge deployment, Minecraft launch, commit, or push occurs in this
  phase. Git reverts all source changes if rollback is needed.
