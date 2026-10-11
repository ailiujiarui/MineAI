"""Prepare or run a fresh Minecraft client evaluation using its real model binding."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import threading
import time
import uuid

from eval_common import ROOT, candidate_patch, digest_files, git, source_files_for, write_json


VERSION = "live-v1"
SUPPORTED_VERSIONS = {VERSION, "survival-v1", "survival-v2", "expedition-v1", "end-resources-v1", "end-entry-v1",
                      "dragon-combat-v1"}
RUNS = ROOT / "build/evals" / VERSION
SCENARIOS = ROOT / "evals" / VERSION / "scenarios"
CRITERIA = {"items", "min_health", "min_food", "dragon_kills", "within", "world"}
PROVIDER_FIELDS = {"id", "name", "provider", "model", "api_key", "base_url",
                   "reasoning_effort", "proxy", "ctx"}


class PreflightError(ValueError):
    """Messages never include input file contents or credential values."""


def read_object(path, label):
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise PreflightError(f"{label} is missing, unreadable, or invalid JSON") from None
    if not isinstance(value, dict):
        raise PreflightError(f"{label} must contain a JSON object")
    return value


def number(value, label, minimum=0, integer=False):
    if (type(value) not in ({int} if integer else {int, float})
            or (type(value) is float and not math.isfinite(value)) or value < minimum):
        raise PreflightError(f"{label} must be a finite {'integer' if integer else 'number'} >= {minimum}")


def position(value, label):
    if not isinstance(value, list) or len(value) != 3:
        raise PreflightError(f"{label} must be a three-coordinate array")
    for coordinate in value:
        number(coordinate, label, minimum=-30_000_000)
        if coordinate > 30_000_000:
            raise PreflightError(f"{label} exceeds the world coordinate limit")


def item_counts(value, label):
    if not isinstance(value, dict):
        raise PreflightError(f"{label} must be an object of item IDs and counts")
    for item, count in value.items():
        if not isinstance(item, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", item):
            raise PreflightError(f"{label} contains an invalid registry ID")
        number(count, label, minimum=1, integer=True)


def criterion(value, label):
    if not isinstance(value, dict) or not value or set(value) - CRITERIA:
        raise PreflightError(f"{label} needs at least one supported world-state criterion")
    if "items" in value:
        item_counts(value["items"], label + ".items")
        if not value["items"]:
            raise PreflightError(f"{label}.items must not be empty")
    for key in ("min_health", "min_food", "dragon_kills"):
        if key in value:
            number(value[key], label + "." + key, minimum=1 if key == "dragon_kills" else 0,
                   integer=key == "dragon_kills")
    if "within" in value:
        within = value["within"]
        if not isinstance(within, dict) or set(within) != {"position", "radius"}:
            raise PreflightError(f"{label}.within needs position and radius")
        position(within["position"], label + ".within.position")
        number(within["radius"], label + ".within.radius")
    if "world" in value:
        world = value["world"]
        if not isinstance(world, dict) or not world:
            raise PreflightError(f"{label}.world must be a nonempty flat object")
        for key, requirement in world.items():
            if not isinstance(key, str) or not key.strip():
                raise PreflightError(f"{label}.world needs nonempty field names")
            if requirement is not True:
                world_number(requirement, label + ".world." + key)


def world_number(value, label):
    number(value, label)
    try:
        finite = math.isfinite(value)
    except OverflowError:
        finite = False
    if not finite:
        raise PreflightError(f"{label} must be a finite nonnegative number")


def world_evidence(snapshot, conditions, label, require_met=False):
    for condition in conditions:
        if "world" not in condition:
            continue
        evidence = snapshot.get("world")
        if not isinstance(evidence, dict):
            raise PreflightError(f"{label}.world must contain required observer evidence")
        for key, requirement in condition["world"].items():
            if key not in evidence:
                raise PreflightError(f"{label}.world is missing a required field")
            if requirement is True:
                if type(evidence[key]) is not bool:
                    raise PreflightError(f"{label}.world.{key} must be boolean")
                if require_met and evidence[key] is not True:
                    raise PreflightError(f"{label}.world does not meet the successful result criterion")
            else:
                world_number(evidence[key], label + ".world." + key)
                if require_met and evidence[key] < requirement:
                    raise PreflightError(f"{label}.world does not meet the successful result criterion")


def end_entry_portal(portal, label, active=False):
    if not isinstance(portal, dict):
        raise PreflightError(f"{label} needs a server portal snapshot")
    dimension = portal.get("dimension")
    if dimension != "minecraft:overworld":
        raise PreflightError(f"{label} must belong to the overworld stronghold")
    for coordinate in ("x", "y", "z"):
        number(portal.get(coordinate), label + "." + coordinate, minimum=-30_000_000, integer=True)
    portal_id = f"{dimension}:{portal['x']}:{portal['y']}:{portal['z']}"
    if (portal.get("portal_id") != portal_id or portal.get("generated_stronghold") is not True
            or portal.get("portal_origin") != "generated_stronghold"):
        raise PreflightError(f"{label} needs one generated stronghold portal identity")
    if type(portal.get("active")) is not bool:
        raise PreflightError(f"{label}.active must be boolean")
    number(portal.get("eyes"), label + ".eyes", integer=True)
    if portal["eyes"] > 12:
        raise PreflightError(f"{label}.eyes exceeds the twelve frames")
    frames, blocks = portal.get("frames"), portal.get("portal_blocks")
    if not isinstance(frames, list) or len(frames) != 12 or not isinstance(blocks, list) or len(blocks) != 9:
        raise PreflightError(f"{label} needs twelve frames and nine interior block observations")
    x, y, z = portal["x"], portal["y"], portal["z"]
    expected_frames = {(x + offset, y, z - 2): "south" for offset in (-1, 0, 1)}
    expected_frames.update({(x + offset, y, z + 2): "north" for offset in (-1, 0, 1)})
    expected_frames.update({(x - 2, y, z + offset): "east" for offset in (-1, 0, 1)})
    expected_frames.update({(x + 2, y, z + offset): "west" for offset in (-1, 0, 1)})
    frame_positions = set()
    for frame in frames:
        if not isinstance(frame, dict):
            raise PreflightError(f"{label}.frames needs block observations")
        location = tuple(frame.get(axis) for axis in ("x", "y", "z"))
        if (any(type(value) is not int for value in location) or location in frame_positions
                or location not in expected_frames or frame.get("id") != "minecraft:end_portal_frame"
                or frame.get("facing") != expected_frames[location] or type(frame.get("has_eye")) is not bool):
            raise PreflightError(f"{label}.frames does not describe one inward-facing portal ring")
        frame_positions.add(location)
    if sum(frame["has_eye"] for frame in frames) != portal["eyes"]:
        raise PreflightError(f"{label}.eyes does not match actual frame states")
    for field in ("initial_eyes", "companion_eye_insertions", "other_eye_insertions"):
        number(portal.get(field), label + "." + field, integer=True)
        if portal[field] > 12:
            raise PreflightError(f"{label}.{field} exceeds the twelve frames")
    if type(portal.get("initial_active")) is not bool:
        raise PreflightError(f"{label}.initial_active must be boolean")
    expected_blocks = {(x + dx, y, z + dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1)}
    block_positions = set()
    for block in blocks:
        if not isinstance(block, dict):
            raise PreflightError(f"{label}.portal_blocks needs block observations")
        location = tuple(block.get(axis) for axis in ("x", "y", "z"))
        if (any(type(value) is not int for value in location) or location in block_positions
                or location not in expected_blocks or not isinstance(block.get("id"), str)):
            raise PreflightError(f"{label}.portal_blocks does not describe one portal interior")
        block_positions.add(location)
    actual_active = portal["eyes"] == 12 and all(block["id"] == "minecraft:end_portal" for block in blocks)
    if portal["active"] != actual_active or (active and not actual_active):
        raise PreflightError(f"{label} lacks the actual active portal blocks")
    if active and (portal["initial_active"] or portal["initial_eyes"] == 12
                   or portal["other_eye_insertions"] != 0
                   or portal["companion_eye_insertions"] != 12 - portal["initial_eyes"]):
        raise PreflightError(f"{label} does not attribute the missing eyes to this companion")
    return portal_id


def end_entry_evidence(snapshot, label):
    world = snapshot.get("world")
    if not isinstance(world, dict):
        raise PreflightError(f"{label}.world needs end-entry observer evidence")
    subject = snapshot.get("companion_uuid")
    try:
        canonical_subject = str(uuid.UUID(subject))
    except (ValueError, TypeError, AttributeError):
        raise PreflightError(f"{label}.companion_uuid needs a subject UUID") from None
    if (subject != canonical_subject or snapshot.get("subject_present") is not True
            or snapshot.get("alive") is not True or world.get("subject_uuid") != subject):
        raise PreflightError(f"{label} does not identify one present living companion")
    for field in ("portal_reached", "portal_activated_by_companion", "native_end_entry", "observation_complete"):
        if type(world.get(field)) is not bool:
            raise PreflightError(f"{label}.world.{field} must be boolean")
    number(snapshot.get("game_tick"), label + ".game_tick", integer=True)
    number(world.get("end_alive_held_ticks"), label + ".world.end_alive_held_ticks", integer=True)
    evidence = world.get("evidence")
    chain = evidence.get("end_entry") if isinstance(evidence, dict) else None
    if not isinstance(chain, dict) or chain.get("subject_uuid") != subject:
        raise PreflightError(f"{label} needs subject-bound raw end-entry evidence")
    number(chain.get("observation_gaps"), label + ".world.evidence.end_entry.observation_gaps", integer=True)
    if world["observation_complete"] is not True or chain["observation_gaps"] != 0:
        raise PreflightError(f"{label} has incomplete end-entry observation")
    if chain.get("critical_evidence_complete") is not True:
        raise PreflightError(f"{label} has incomplete critical end-entry evidence")
    if type(chain.get("native_entry_episode_active")) is not bool:
        raise PreflightError(f"{label} must report the current native entrance episode")
    if chain.get("first_death_observed") is not False:
        raise PreflightError(f"{label} needs explicit evidence that no first death was observed")
    if chain.get("last_verified_game_tick") != snapshot["game_tick"]:
        raise PreflightError(f"{label} has stale end-entry tick evidence")
    flags = (world["portal_reached"], world["portal_activated_by_companion"], world["native_end_entry"])
    if (flags[1] and not flags[0]) or (flags[2] and not flags[1]):
        raise PreflightError(f"{label} has an incomplete portal milestone chain")
    if flags[2] and chain["native_entry_episode_active"] is not True:
        raise PreflightError(f"{label} cannot reuse an earlier End visit")
    portal_id = None
    baseline = None
    previous_tick = -1
    for required, field, kind in zip(flags, ("arrival", "activation", "entry"),
                                     ("portal_reached", "portal_activated", "native_end_entry")):
        if not required:
            continue
        event = chain.get(field)
        if (not isinstance(event, dict) or event.get("kind") != kind or event.get("subject_uuid") != subject):
            raise PreflightError(f"{label}.{field} lacks attributed native evidence")
        number(event.get("game_tick"), label + "." + field + ".game_tick", integer=True)
        for axis in ("x", "y", "z"):
            number(event.get(axis), label + "." + field + "." + axis, minimum=-30_000_000)
        if not previous_tick <= event["game_tick"] <= snapshot["game_tick"]:
            raise PreflightError(f"{label} has an out-of-order portal chain")
        previous_tick = event["game_tick"]
        event_portal_id = end_entry_portal(event.get("portal"), label + "." + field + ".portal", field != "arrival")
        if event.get("portal_id") != event_portal_id or (portal_id is not None and portal_id != event_portal_id):
            raise PreflightError(f"{label} combines events from different portals")
        portal_id = event_portal_id
        event_baseline = (event["portal"]["initial_eyes"], event["portal"]["initial_active"])
        if baseline is not None and baseline != event_baseline:
            raise PreflightError(f"{label} changes the portal's initial state across events")
        baseline = event_baseline
        if field in {"arrival", "activation"} and event.get("dimension") != event["portal"]["dimension"]:
            raise PreflightError(f"{label}.{field} belongs to a different dimension")
        if field == "arrival":
            if sum((event[axis] - event["portal"][axis] - 0.5) ** 2 for axis in ("x", "y", "z")) > 36:
                raise PreflightError(f"{label}.arrival does not prove reaching this portal")
        if field == "activation" and (event.get("native_eye_inserted") is not True
                                      or event.get("actor_uuid") != subject
                                      or type(event.get("eye_inventory_delta")) is not int
                                      or event["eye_inventory_delta"] != -1):
            raise PreflightError(f"{label}.activation lacks a committed native eye insertion")
        if field == "activation":
            frame = event.get("frame")
            frames = event["portal"]["frames"]
            if (not isinstance(frame, dict) or
                    tuple(frame.get(axis) for axis in ("x", "y", "z")) not in
                    {tuple(item[axis] for axis in ("x", "y", "z")) for item in frames}):
                raise PreflightError(f"{label}.activation did not interact with this portal ring")
        if field == "entry" and (event.get("evidence_source") != "vanilla_handle_portal_change_dimension"
                                 or event.get("destination_uuid") != subject
                                 or event.get("from_dimension") != event["portal"]["dimension"]
                                 or event.get("to_dimension") != "minecraft:the_end"
                                 or event.get("dimension") != "minecraft:the_end"):
            raise PreflightError(f"{label}.entry lacks vanilla entrance provenance")
        if field == "entry":
            source = event.get("source_block")
            blocks = event["portal"]["portal_blocks"]
            if (not isinstance(source, dict) or
                    tuple(source.get(axis) for axis in ("x", "y", "z")) not in
                    {tuple(block[axis] for axis in ("x", "y", "z")) for block in blocks}):
                raise PreflightError(f"{label}.entry lacks this portal's native source block")
    if flags[2] and snapshot.get("dimension") != "minecraft:the_end":
        raise PreflightError(f"{label} native entrance requires the companion to remain in the End")
    if portal_id is not None:
        if end_entry_portal(chain.get("portal"), label + ".portal") != portal_id:
            raise PreflightError(f"{label} current portal identity differs from the native chain")
        if (chain["portal"]["initial_eyes"], chain["portal"]["initial_active"]) != baseline:
            raise PreflightError(f"{label} current portal initial state differs from the native chain")
        if flags[1] and (chain["portal"]["other_eye_insertions"] != 0
                         or chain["portal"]["companion_eye_insertions"]
                         != chain["activation"]["portal"]["companion_eye_insertions"]):
            raise PreflightError(f"{label} current portal contributions differ from the native activation")
    if flags[2] and world["end_alive_held_ticks"] > snapshot["game_tick"] - chain["entry"]["game_tick"] + 1:
        raise PreflightError(f"{label} hold predates the native entrance")
    if not flags[2] and world["end_alive_held_ticks"] != 0:
        raise PreflightError(f"{label} cannot hold an unverified entrance")
    return chain


def dragon_combat_evidence(snapshot, label, allow_calibration_fixture=False):
    world = snapshot.get("world")
    evidence = world.get("evidence") if isinstance(world, dict) else None
    chain = evidence.get("dragon_combat") if isinstance(evidence, dict) else None
    if not isinstance(chain, dict):
        raise PreflightError(f"{label} needs raw dragon-combat evidence")
    subject = snapshot.get("companion_uuid")
    target = chain.get("target_uuid")
    for value, field in ((subject, "companion_uuid"), (target, "target_uuid")):
        try:
            canonical = str(uuid.UUID(value))
        except (ValueError, TypeError, AttributeError):
            raise PreflightError(f"{label}.{field} needs a canonical UUID") from None
        if value != canonical:
            raise PreflightError(f"{label}.{field} needs a canonical UUID")
    fixture = chain.get("calibration_fixture")
    if type(fixture) is not bool or (fixture and not allow_calibration_fixture):
        raise PreflightError(f"{label} cannot count an artificial combat fixture as a formal End battle")
    dimension = chain.get("dimension") if fixture else "minecraft:the_end"
    if not isinstance(dimension, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", dimension):
        raise PreflightError(f"{label} needs the native battle dimension")
    identity = {"subject_uuid": subject, "target_uuid": target, "origin": chain.get("origin"),
                "fight_id": chain.get("fight_id"), "dimension": dimension}
    if (chain.get("subject_uuid") != subject or world.get("subject_uuid") != subject
            or chain.get("dimension") != identity["dimension"]
            or not isinstance(identity["fight_id"], str) or not identity["fight_id"].strip()):
        raise PreflightError(f"{label} must identify one companion and native End fight")
    number(snapshot.get("game_tick"), label + ".game_tick", integer=True)
    number(snapshot.get("dragon_kills"), label + ".dragon_kills", integer=True)
    number(snapshot.get("deaths"), label + ".deaths", integer=True)
    for field in ("native_dragon_kill", "dragon_combat_ready", "observation_complete", "dragon_damaged",
                  "dragon_death_started"):
        if type(world.get(field)) is not bool:
            raise PreflightError(f"{label}.world.{field} must be boolean")
    for field in ("first_death_observed", "critical_evidence_complete", "attribution_ambiguous"):
        if type(chain.get(field)) is not bool:
            raise PreflightError(f"{label}.dragon_combat.{field} must be boolean")
    if chain["attribution_ambiguous"]:
        raise PreflightError(f"{label} cannot score ambiguous native damage attribution as a combat outcome")
    number(chain.get("observation_gaps"), label + ".dragon_combat.observation_gaps", integer=True)
    if (world["observation_complete"] is not True or chain["observation_gaps"] != 0
            or chain["critical_evidence_complete"] is not True
            or chain.get("last_verified_game_tick") != snapshot["game_tick"]):
        raise PreflightError(f"{label} has incomplete or stale dragon-combat observation")
    if chain["first_death_observed"] or snapshot.get("alive") is not True or snapshot.get("subject_present") is not True or snapshot["deaths"] != 0:
        raise PreflightError(f"{label} cannot follow a companion death or missing body")
    subject_dimension = snapshot.get("dimension")
    if not isinstance(subject_dimension, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", subject_dimension):
        raise PreflightError(f"{label} needs the companion's actual dimension")
    if subject_dimension != dimension and not isinstance(chain.get("fight_end"), dict):
        raise PreflightError(f"{label} requires the companion to remain in the End")
    number(snapshot.get("health"), label + ".health")
    if snapshot["health"] <= 0:
        raise PreflightError(f"{label} must corroborate a living companion with positive health")

    def bound(event, field, source):
        if (not isinstance(event, dict) or any(event.get(key) != value for key, value in identity.items())
                or event.get("evidence_source") != source):
            raise PreflightError(f"{label}.{field} lacks this fight's native evidence")
        number(event.get("game_tick"), label + "." + field + ".game_tick", integer=True)
        if event["game_tick"] > snapshot["game_tick"]:
            raise PreflightError(f"{label}.{field} occurs after the snapshot")
        for actor_field in ("actor_uuid", "source_actor_uuid", "direct_entity_uuid", "credited_actor_uuid", "provenance_actor_uuid"):
            value = event.get(actor_field)
            if value is None:
                continue
            try:
                canonical = str(uuid.UUID(value))
            except (ValueError, TypeError, AttributeError):
                raise PreflightError(f"{label}.{field}.{actor_field} needs a native entity UUID") from None
            if value != canonical:
                raise PreflightError(f"{label}.{field}.{actor_field} needs a native entity UUID")
        return event

    initial = bound(chain.get("initial_battle"), "initial_battle", "server_native_dragon_fight_initial_state")
    origin = initial.get("origin")
    if (not isinstance(origin, list) or len(origin) != 3 or any(type(value) is not int for value in origin)
            or identity["fight_id"] != f"{dimension}:{origin[0]}:{origin[1]}:{origin[2]}:{target}"):
        raise PreflightError(f"{label}.initial_battle must identify the native fight origin and target")
    if initial.get("calibration_fixture") is not fixture:
        raise PreflightError(f"{label}.initial_battle must disclose the same fixture origin")
    if (type(initial.get("dragon_kills")) is not int or initial["dragon_kills"] != 0
            or initial.get("target_present") is not True or initial.get("fight_bound") is not True):
        raise PreflightError(f"{label}.initial_battle must start with one living bound target and no previous kill")
    for field, expected in (("fight_bound", True), ("needs_state_scanning", False), ("dragon_killed", False),
                            ("previously_killed", False), ("is_respawning", False), ("target_present", True),
                            ("target_no_ai", False), ("target_invulnerable", False)):
        if initial.get(field) is not expected and not fixture:
            raise PreflightError(f"{label}.initial_battle is not a normal initial native dragon fight")
    if not fixture and (type(initial.get("target_health")) not in {int, float} or initial["target_health"] != 200
            or type(initial.get("target_max_health")) not in {int, float} or initial["target_max_health"] != 200
            or type(initial.get("dragon_kills")) is not int or initial["dragon_kills"] != 0
            or type(initial.get("crystals_alive")) is not int or initial["crystals_alive"] != 10
            or type(initial.get("death_animation_ticks")) is not int or initial["death_animation_ticks"] != 0
            or not isinstance(initial.get("phase"), str) or initial["phase"].lower().startswith("dying")):
        raise PreflightError(f"{label}.initial_battle needs full health, ten crystals and zero historical kills")
    crystals = initial.get("crystals")
    if not isinstance(crystals, list) or (not fixture and len(crystals) != 10):
        raise PreflightError(f"{label}.initial_battle needs ten individual crystal observations")
    crystal_ids = set()
    for crystal in crystals:
        if (not isinstance(crystal, dict) or crystal.get("invulnerable") is not False
                or (not fixture and crystal.get("alive") is not True)):
            raise PreflightError(f"{label}.initial_battle contains a missing or invulnerable crystal")
        try:
            crystal_id = str(uuid.UUID(crystal.get("uuid")))
        except (ValueError, TypeError, AttributeError):
            raise PreflightError(f"{label}.initial_battle needs crystal UUIDs") from None
        if crystal.get("uuid") != crystal_id or crystal_id in crystal_ids:
            raise PreflightError(f"{label}.initial_battle needs ten different crystal UUIDs")
        crystal_ids.add(crystal_id)
        position(crystal.get("position"), label + ".initial_battle.crystal.position")
    current = chain.get("current_battle")
    if not isinstance(current, dict) or any(current.get(key) != value for key, value in identity.items()):
        raise PreflightError(f"{label}.current_battle must observe the same native target")
    if current.get("game_tick") != snapshot["game_tick"]:
        raise PreflightError(f"{label}.current_battle is stale")
    if (current.get("calibration_fixture") is not fixture or current.get("origin") != initial["origin"]
            or current.get("fight_bound") is not True
            or current.get("needs_state_scanning") is not False or current.get("is_respawning") is not False
            or (not fixture and (current.get("target_no_ai") is not False or current.get("target_invulnerable") is not False))):
        raise PreflightError(f"{label}.current_battle no longer observes the same normal native fight")
    for field in ("target_present", "dragon_killed", "previously_killed"):
        if type(current.get(field)) is not bool:
            raise PreflightError(f"{label}.current_battle.{field} must be boolean")
    for field in ("death_animation_ticks", "crystals_alive", "dragon_kills"):
        number(current.get(field), label + ".current_battle." + field, integer=True)
    if current["dragon_kills"] != snapshot["dragon_kills"]:
        raise PreflightError(f"{label} must corroborate the native kill statistic")
    world_number(current.get("target_max_health"), label + ".current_battle.target_max_health")
    if current["target_max_health"] != 200:
        raise PreflightError(f"{label} changed the target's native maximum health")
    if current["target_present"]:
        world_number(current.get("target_health"), label + ".current_battle.target_health")
        if current["target_health"] > current["target_max_health"]:
            raise PreflightError(f"{label} target health exceeds its maximum")
    elif current.get("target_health") is not None:
        raise PreflightError(f"{label} cannot invent health for a missing target")
    for field in ("total_actual_damage", "companion_actual_damage", "minimum_health"):
        world_number(chain.get(field), label + ".dragon_combat." + field)
    if (chain["companion_actual_damage"] > chain["total_actual_damage"]
            or chain["minimum_health"] > 200):
        raise PreflightError(f"{label} has inconsistent actual-damage or minimum-health evidence")
    if chain.get("current_health") != current.get("target_health"):
        raise PreflightError(f"{label} current dragon health differs from the native sample")

    def actual_damage(event, field, companion=False):
        event = bound(event, field, "native_ender_dragon_really_hurt")
        number(event.get("event_id"), label + "." + field + ".event_id", minimum=1, integer=True)
        for damage_field in ("health_before", "health_after", "actual_health_loss"):
            world_number(event.get(damage_field), label + "." + field + "." + damage_field)
        if (event["health_before"] <= event["health_after"] or event["health_before"] > 200
                or not math.isclose(event["actual_health_loss"], event["health_before"] - event["health_after"],
                                    rel_tol=1e-5, abs_tol=0.0001)
                or event["actual_health_loss"] > chain["total_actual_damage"]):
            raise PreflightError(f"{label}.{field} must describe one accepted actual health loss")
        if companion and (event.get("provenance_actor_uuid") != subject or event.get("attribution_reliable") is not True
                          or event.get("attribution_origin") not in {"native_damage_source", "crystal_explicit_player"}):
            raise PreflightError(f"{label}.{field} must reliably attribute actual damage to this companion")
        return event

    first_damage = chain.get("first_companion_damage")
    if first_damage is not None:
        first_damage = actual_damage(first_damage, "first_companion_damage", companion=True)
        if first_damage["actual_health_loss"] > chain["companion_actual_damage"]:
            raise PreflightError(f"{label} companion damage total is smaller than its native first event")
    if world["dragon_damaged"] != (first_damage is not None):
        raise PreflightError(f"{label} damage milestone lacks companion-attributed actual damage")
    first_world_damage = chain.get("first_damage")
    if first_world_damage is not None:
        first_world_damage = actual_damage(first_world_damage, "first_damage")
    if (first_world_damage is None) != (chain["total_actual_damage"] == 0):
        raise PreflightError(f"{label} cumulative damage must retain its first native damage receipt")
    diagnostic_events = evidence.get("events")
    if isinstance(diagnostic_events, list):
        damage_ids = set()
        observed_total = observed_companion = 0
        for event in diagnostic_events:
            if not isinstance(event, dict) or event.get("kind") != "dragon_damage":
                continue
            event = actual_damage(event, "events.dragon_damage")
            if event["event_id"] in damage_ids:
                raise PreflightError(f"{label} diagnostic damage events repeat a native event")
            damage_ids.add(event["event_id"])
            observed_total += event["actual_health_loss"]
            if event.get("attribution_reliable") is True and event.get("provenance_actor_uuid") == subject:
                observed_companion += event["actual_health_loss"]
        if evidence.get("events_truncated") is False and (
                not math.isclose(observed_total, chain["total_actual_damage"], rel_tol=1e-5, abs_tol=0.001)
                or not math.isclose(observed_companion, chain["companion_actual_damage"], rel_tol=1e-5, abs_tol=0.001)):
            raise PreflightError(f"{label} damage counters differ from the complete native event ledger")

    prior_tick = initial["game_tick"]
    terminal = {}
    for field, source in (("lethal", "native_living_entity_die"),
                          ("kill_credit", "native_server_player_award_kill_score"),
                          ("kill_statistic", "native_player_killed_entity_statistic"),
                          ("fight_end", "native_end_dragon_fight_set_dragon_killed")):
        event = chain.get(field)
        if event is None:
            continue
        event = bound(event, field, source)
        if event["game_tick"] < prior_tick:
            raise PreflightError(f"{label} has an out-of-order native death chain")
        prior_tick = event["game_tick"]
        terminal[field] = event
    lethal, credit, statistic, ended = (terminal.get(field) for field in
                                       ("lethal", "kill_credit", "kill_statistic", "fight_end"))
    if (credit is not None or statistic is not None or ended is not None) and lethal is None:
        raise PreflightError(f"{label} cannot infer native death from credit, statistics or battle state")
    if lethal is not None:
        if type(lethal.get("health")) not in {int, float} or lethal["health"] != 0:
            raise PreflightError(f"{label}.lethal needs the native fatal health state")
        if lethal.get("attribution_origin") not in {"native_damage_source", "crystal_explicit_player",
                                                    "crystal_nearest_player_fallback", "unverified_damage_origin"}:
            raise PreflightError(f"{label}.lethal needs explicit damage provenance")
        if (lethal["attribution_origin"] == "crystal_nearest_player_fallback"
                and chain["attribution_ambiguous"] is not True):
            raise PreflightError(f"{label} cannot hide nearest-player crystal attribution ambiguity")
        fatal = lethal.get("fatal_damage")
        if fatal is not None:
            fatal = actual_damage(fatal, "lethal.fatal_damage")
            if (fatal["game_tick"] != lethal["game_tick"] or fatal["health_after"] != 0
                    or fatal.get("event_id") != lethal.get("damage_event_id")
                    or any(lethal.get(field) != fatal.get(field) for field in
                           ("health_before", "health_after", "actual_health_loss", "attribution_origin",
                            "provenance_actor_uuid", "source_actor_uuid", "direct_entity_uuid", "damage_type_message_id"))):
                raise PreflightError(f"{label}.lethal must bind the matching accepted fatal damage receipt")
            if fatal.get("attribution_reliable") is not True:
                raise PreflightError(f"{label}.lethal does not reliably attribute the fatal damage")
        elif credit is not None or statistic is not None or ended is not None:
            raise PreflightError(f"{label} native death lacks accepted fatal damage; direct state edits are not combat")
    if world["dragon_death_started"] != (lethal is not None and isinstance(lethal.get("fatal_damage"), dict)):
        raise PreflightError(f"{label} death milestone lacks actual-damage-backed native death")
    retained_damage = [event for event in (first_world_damage, first_damage,
                                          lethal.get("fatal_damage") if lethal else None) if isinstance(event, dict)]
    for index, event in enumerate(retained_damage):
        for other in retained_damage[index + 1:]:
            if event["event_id"] == other["event_id"] and event != other:
                raise PreflightError(f"{label} repeated native damage receipts disagree about one event")
    if credit is not None and credit.get("actor_uuid") != lethal.get("credited_actor_uuid"):
        raise PreflightError(f"{label} native kill-credit actor differs from the native lethal receipt")
    if credit is not None and credit.get("actor_uuid") is None:
        raise PreflightError(f"{label} native player kill credit must identify its actor")
    if credit is not None and any(credit.get(field) != lethal.get(field) for field in
                                  ("source_actor_uuid", "direct_entity_uuid", "damage_type_message_id")):
        raise PreflightError(f"{label} native player credit differs from the lethal damage source")
    if statistic is not None:
        if credit is None or statistic.get("actor_uuid") != credit.get("actor_uuid"):
            raise PreflightError(f"{label} kill statistic lacks matching native player credit")
        number(statistic.get("before"), label + ".kill_statistic.before", integer=True)
        number(statistic.get("after"), label + ".kill_statistic.after", integer=True)
        if statistic["after"] != statistic["before"] + 1:
            raise PreflightError(f"{label} needs one native kill-statistic increment")
        if statistic["actor_uuid"] == subject and (statistic["before"] != 0
                                                   or statistic["after"] != snapshot["dragon_kills"]):
            raise PreflightError(f"{label} cannot reuse another dragon kill or historical statistic")
    if ended is not None and (ended.get("dragon_killed") is not True or ended.get("previously_killed") is not True
                              or type(ended.get("death_animation_ticks")) is not int
                              or ended["death_animation_ticks"] != 200
                              or current["dragon_killed"] is not True or current["previously_killed"] is not True):
        raise PreflightError(f"{label} must await the native dragon death animation and battle completion")
    terminal_complete = (len(terminal) == 4 and credit.get("actor_uuid") == subject
                and statistic.get("actor_uuid") == subject and snapshot["dragon_kills"] == 1
                and lethal.get("source_actor_uuid") == subject
                and lethal.get("provenance_actor_uuid") == subject
                and chain["attribution_ambiguous"] is False
                and lethal.get("attribution_origin") != "crystal_nearest_player_fallback"
                and not chain["first_death_observed"]
                and snapshot["game_tick"] > ended["game_tick"])
    complete = terminal_complete and not fixture
    if world["native_dragon_kill"] != complete:
        raise PreflightError(f"{label} native kill fact is not corroborated by this fight's complete chain")
    held = world.get("dragon_post_kill_alive_ticks")
    number(held, label + ".world.dragon_post_kill_alive_ticks", integer=True)
    number(chain.get("post_kill_alive_ticks"), label + ".dragon_combat.post_kill_alive_ticks", integer=True)
    if held != chain["post_kill_alive_ticks"]:
        raise PreflightError(f"{label} survival hold differs from its retained server-tick evidence")
    if held > 0:
        if (not terminal_complete or chain.get("post_kill_hold_start_game_tick") != ended["game_tick"] + 1
                or chain.get("post_kill_last_verified_game_tick") != snapshot["game_tick"]
                or held != snapshot["game_tick"] - ended["game_tick"]):
            raise PreflightError(f"{label} survival hold must follow the native fight end on verified server ticks")
    elif chain.get("post_kill_hold_start_game_tick") is not None or chain.get("post_kill_last_verified_game_tick") is not None:
        raise PreflightError(f"{label} cannot attach survival-hold ticks to an unstarted hold")
    if terminal_complete and held == 0 and not fixture:
        raise PreflightError(f"{label} settled native completion must include its first verified alive tick")
    if not current["target_present"] and ended is None:
        raise PreflightError(f"{label} target disappeared without native battle-completion evidence")
    if world["dragon_combat_ready"] is not True and not fixture:
        raise PreflightError(f"{label} initial native battle was not prepared successfully")
    if fixture and world["dragon_combat_ready"] is not False:
        raise PreflightError(f"{label} artificial fixture must remain outside formal battle readiness")
    return chain


def dragon_combat_result_evidence(result, scenario):
    initial, final = result["initial_snapshot"], result["final_snapshot"]
    initial_chain = dragon_combat_evidence(initial, "initial_snapshot")
    if (initial.get("dragon_kills") != 0 or initial.get("deaths") != 0
            or initial["world"]["native_dragon_kill"] or initial_chain["first_death_observed"]
            or any(initial_chain.get(field) is not None for field in
                   ("first_damage", "first_companion_damage", "lethal", "kill_credit", "kill_statistic", "fight_end"))
            or initial_chain["current_battle"].get("target_present") is not True
            or initial_chain["current_battle"].get("target_health") != 200
            or initial_chain["current_battle"].get("dragon_killed") is not False
            or initial_chain["current_battle"].get("previously_killed") is not False
            or initial_chain["current_battle"].get("death_animation_ticks") != 0
            or not isinstance(initial_chain["current_battle"].get("phase"), str)
            or initial_chain["current_battle"]["phase"].lower().startswith("dying")
            or initial_chain["total_actual_damage"] != 0 or initial_chain["companion_actual_damage"] != 0
            or initial_chain["minimum_health"] != 200):
        raise PreflightError("Dragon-combat initial snapshot cannot contain earlier deaths or kills")
    final_world = final.get("world")
    final_evidence = final_world.get("evidence") if isinstance(final_world, dict) else None
    final_chain = final_evidence.get("dragon_combat") if isinstance(final_evidence, dict) else None
    latched = isinstance(final_chain, dict) and final_chain.get("first_death_observed") is True
    retained_death = isinstance(final_chain, dict) and final_chain.get("first_death") is not None
    died = final.get("alive") is False or (type(final.get("deaths")) is int and final["deaths"] > 0) or latched or retained_death
    if result["status"] == "failed" and result["reason"] == "companion_died" and died:
        if final.get("companion_uuid") != initial["companion_uuid"]:
            raise PreflightError("Dragon-combat death evidence must belong to this companion")
        if (latched or retained_death) and final_chain.get("subject_uuid") != initial["companion_uuid"]:
            raise PreflightError("Dragon-combat first death must belong to this companion")
        if latched or retained_death:
            death = final_chain.get("first_death")
            if (not isinstance(death, dict) or death.get("subject_uuid") != initial["companion_uuid"]
                    or death.get("evidence_source") not in {"native_companion_death_before_recovery", "server_tick_dead_body"}
                    or type(death.get("game_tick")) is not int or type(final.get("game_tick")) is not int
                    or not initial["game_tick"] <= death["game_tick"] <= final["game_tick"]):
                raise PreflightError("Dragon-combat first death needs a retained native death receipt")
        return
    if died:
        raise PreflightError("Dragon-combat first death must terminate as companion_died before success or budget")
    final_chain = dragon_combat_evidence(final, "final_snapshot")
    for field in ("subject_uuid", "target_uuid", "fight_id", "dimension", "initial_battle"):
        if final_chain.get(field) != initial_chain.get(field):
            raise PreflightError("Dragon-combat snapshots cannot combine different subjects, targets or native fights")
    if (final_chain["total_actual_damage"] < initial_chain["total_actual_damage"]
            or final_chain["companion_actual_damage"] < initial_chain["companion_actual_damage"]
            or final_chain["minimum_health"] > initial_chain["minimum_health"]):
        raise PreflightError("Dragon-combat cumulative damage and minimum health cannot rewind between snapshots")
    conditions = [scenario["success"], *(milestone["condition"] for milestone in scenario.get("milestones", []))]
    world_evidence(initial, conditions, "initial_snapshot")
    world_evidence(final, conditions, "final_snapshot", require_met=result["status"] == "success")
    if result["status"] == "success":
        if final_world["native_dragon_kill"] is not True:
            raise PreflightError("Dragon-combat success needs native lethal, player credit, statistic and battle-end evidence")
        if "success_hold_ticks" in scenario:
            number(result.get("success_held_ticks"), "result.success_held_ticks",
                   minimum=scenario["success_hold_ticks"], integer=True)
        number(final_world.get("dragon_post_kill_alive_ticks"), "final_snapshot.world.dragon_post_kill_alive_ticks",
               minimum=scenario["success_hold_ticks"], integer=True)
    elif final_world["native_dragon_kill"] and final_world["dragon_post_kill_alive_ticks"] >= scenario["success_hold_ticks"]:
        raise PreflightError("A completed native dragon kill cannot be reported as an unfinished battle")
    if result["status"] == "failed" and result["reason"] == "target_killed_by_other":
        lethal = final_chain.get("lethal")
        credit = final_chain.get("kill_credit")
        if (not isinstance(lethal, dict) or not isinstance(credit, dict)
                or credit.get("actor_uuid") == initial["companion_uuid"]
                or credit.get("actor_uuid") != lethal.get("credited_actor_uuid")):
            raise PreflightError("Another player's dragon kill needs attributed native target-death evidence")


def validate_scenario(value):
    if set(value) - {"evaluation_version", "case_id", "split", "source", "prompt", "seed",
                     "world_preset", "setup", "success", "milestones", "budget",
                     "world_observer", "success_hold_ticks"}:
        raise PreflightError("Scenario contains unsupported fields")
    if (not isinstance(value.get("evaluation_version"), str)
            or value["evaluation_version"] not in SUPPORTED_VERSIONS):
        raise PreflightError("Scenario has the wrong evaluation_version")
    observer = {"survival-v2": "free-survival-v2", "expedition-v1": "expedition-v1",
                "end-resources-v1": "end-resources-v1", "end-entry-v1": "end-entry-v1",
                "dragon-combat-v1": "dragon-combat-v1"}.get(
        value["evaluation_version"], "sustainable-survival-v1")
    if "world_observer" in value and value["world_observer"] != observer:
        raise PreflightError("Scenario has an unsupported world_observer")
    if "success_hold_ticks" in value:
        number(value["success_hold_ticks"], "success_hold_ticks", minimum=1, integer=True)
    if not isinstance(value.get("case_id"), str) or not re.fullmatch(r"[a-z0-9_.-]+", value["case_id"]):
        raise PreflightError("Scenario needs a stable lowercase case_id")
    if not isinstance(value.get("prompt"), str) or not value["prompt"].strip():
        raise PreflightError("Scenario needs a nonempty player prompt")
    if value.get("split") != "dev":
        raise PreflightError("Live scenarios are exploratory dev cases")
    if value.get("world_preset") not in {"flat", "normal"}:
        raise PreflightError("world_preset must be flat or normal")
    number(value.get("seed"), "seed", minimum=-(2 ** 63), integer=True)
    if value["seed"] >= 2 ** 63:
        raise PreflightError("seed must fit a signed 64-bit integer")
    if "budget" in value:
        raise PreflightError("Budgets must be provided explicitly on the command line")
    setup = value.get("setup", {})
    setup_fields = {"spawn", "floor", "blocks", "inventory"}
    if value["evaluation_version"] == "survival-v2":
        setup_fields |= {"food_level", "saturation"}
    if value["evaluation_version"] in {"end-resources-v1", "end-entry-v1", "dragon-combat-v1"}:
        setup_fields.add("equipment")
    if value["evaluation_version"] == "dragon-combat-v1":
        setup_fields = {"spawn", "inventory", "equipment", "dimension", "native_dragon_battle"}
    if not isinstance(setup, dict) or set(setup) - setup_fields:
        raise PreflightError("Unsupported setup fields")
    if value["evaluation_version"] == "dragon-combat-v1":
        if (value.get("world_observer") != "dragon-combat-v1" or value["world_preset"] != "normal"
                or setup.get("dimension") != "minecraft:the_end" or setup.get("native_dragon_battle") is not True):
            raise PreflightError("dragon-combat-v1 requires a normal fresh native End battle and its observer")
        if setup.get("spawn") != [100.5, 49.0, 0.5]:
            raise PreflightError("dragon-combat-v1 requires the public native End arrival platform spawn")
        if type(value.get("success_hold_ticks")) is not int or value["success_hold_ticks"] != 200:
            raise PreflightError("dragon-combat-v1 requires 200 verified alive server ticks after the native fight end")
    if value["evaluation_version"] == "survival-v2":
        food_level = setup.get("food_level", 20)
        number(food_level, "setup.food_level", integer=True)
        if food_level > 20:
            raise PreflightError("setup.food_level must be <= 20")
        if "saturation" in setup:
            saturation = setup["saturation"]
            number(saturation, "setup.saturation")
            if saturation > food_level:
                raise PreflightError("setup.saturation must be <= setup.food_level")
    if "spawn" in setup:
        position(setup["spawn"], "setup.spawn")
    if "inventory" in setup:
        item_counts(setup["inventory"], "setup.inventory")
    if "equipment" in setup:
        equipment = setup["equipment"]
        if (not isinstance(equipment, dict)
                or set(equipment) - {"head", "chest", "legs", "feet", "offhand"}):
            raise PreflightError("setup.equipment needs supported equipment slots")
        for item in equipment.values():
            if not isinstance(item, str):
                raise PreflightError("setup.equipment values must be item registry IDs")
            item_counts({item: 1}, "setup.equipment")
    if "floor" in setup:
        floor = setup["floor"]
        if not isinstance(floor, dict) or set(floor) != {"from", "to", "block"}:
            raise PreflightError("setup.floor needs from, to, and block")
        position(floor["from"], "setup.floor.from")
        position(floor["to"], "setup.floor.to")
        item_counts({floor["block"]: 1}, "setup.floor.block")
        volume = math.prod(abs(b - a) + 1 for a, b in zip(floor["from"], floor["to"]))
        if volume > 100_000:
            raise PreflightError("setup.floor exceeds the 100,000 block setup limit")
    blocks = setup.get("blocks", [])
    if not isinstance(blocks, list):
        raise PreflightError("setup.blocks must be an array")
    for block in blocks:
        if not isinstance(block, dict) or set(block) != {"pos", "block"}:
            raise PreflightError("Each setup block needs pos and block")
        position(block["pos"], "setup.blocks.pos")
        item_counts({block["block"]: 1}, "setup.blocks.block")
    criterion(value.get("success"), "success")
    milestones = value.get("milestones", [])
    if not isinstance(milestones, list):
        raise PreflightError("milestones must be an array")
    seen = set()
    for milestone in milestones:
        if (not isinstance(milestone, dict) or set(milestone) != {"id", "condition"}
                or not isinstance(milestone["id"], str) or not milestone["id"].strip()
                or milestone["id"] in seen):
            raise PreflightError("Milestones need unique IDs and conditions")
        seen.add(milestone["id"])
        criterion(milestone["condition"], "milestone.condition")
    return value


def select_provider(path, provider_id):
    library = read_object(path, "Provider library")
    entries = library.get("entries")
    if not isinstance(entries, list):
        raise PreflightError("Provider library needs an entries array")
    matches = [entry for entry in entries if isinstance(entry, dict) and entry.get("id") == provider_id]
    if len(matches) != 1:
        raise PreflightError("Provider ID must match exactly one library entry")
    entry = {key: value for key, value in matches[0].items() if key in PROVIDER_FIELDS}
    for key in ("id", "name", "provider", "model"):
        if not isinstance(entry.get(key), str) or not entry[key].strip():
            raise PreflightError(f"Selected provider entry has an empty or invalid {key}")
    for key in ("api_key", "base_url", "reasoning_effort", "proxy"):
        if key in entry and entry[key] is not None and not isinstance(entry[key], str):
            raise PreflightError(f"Selected provider entry has an invalid {key}")
    if "ctx" in entry:
        number(entry["ctx"], "Provider context window", integer=True)
    return entry


def redact(text, secrets):
    for secret in sorted(set(filter(None, secrets)), key=len, reverse=True):
        text = text.replace(secret, "[REDACTED]")
        text = text.replace(json.dumps(secret)[1:-1], "[REDACTED]")
    return text


def capture_companion_chat(output, secrets):
    """Retain the isolated companion's full conversation after its client exits."""
    sources = sorted(path for path in (output / "game/config/numen/companions").glob("*/chat.jsonl")
                     if path.is_file())
    if not sources:
        return {"status": "unavailable", "reason": "No companion conversation was written"}
    if len(sources) != 1:
        raise PreflightError("Expected exactly one isolated companion conversation")
    source = sources[0]
    captured = redact(source.read_bytes().decode("utf-8"), secrets).encode("utf-8")
    target = output / "results/chat.jsonl"
    target.write_bytes(captured)
    return {"status": "captured", "companion_uuid": source.parent.name,
            "source_ref": source.relative_to(output).as_posix(),
            "file_ref": target.relative_to(output).as_posix(),
            "sha256": hashlib.sha256(captured).hexdigest()}


def command_for(output, offline=False, neoforge_version=None):
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), ":core:neoforge:runClient", "--no-daemon", "--console=plain",
               f"-PnumenLiveEval={output / 'request.json'}",
               f"-PnumenLiveEvalGameDir={output / 'game'}"]
    if offline:
        command.append("--offline")
    if neoforge_version:
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", neoforge_version):
            raise PreflightError("NeoForge override must be a numeric three-part version")
        command.append(f"-Pneoforge_version={neoforge_version}")
    # Windows runs .bat through cmd.exe even with shell=False. Never allow its
    # expansion/control characters in the wrapper path or generated arguments.
    if os.name == "nt" and any(re.search(r'[&|<>^%!"\r\n()]', arg) for arg in command):
        raise PreflightError("Windows launcher paths must not contain cmd.exe control characters")
    return command


def stop_owned_process(process, log):
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=log, stderr=subprocess.STDOUT, check=False)
    else:
        os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=30)


def launch(command, output, timeout, secrets):
    started = time.monotonic()
    with (output / "launcher.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                                   creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
                                   start_new_session=os.name != "nt")

        def drain():
            try:
                for line in process.stdout:
                    log.write(redact(line, secrets))
                    log.flush()
            finally:
                process.stdout.close()

        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        timed_out = False
        interrupted = False
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            stop_owned_process(process, log)
            code = 124
        except KeyboardInterrupt:
            interrupted = True
            stop_owned_process(process, log)
            code = 130
        finally:
            reader.join(timeout=30)
        return {"argv": command, "exit_code": code, "timeout": timed_out, "interrupted": interrupted,
                "duration_ms": round((time.monotonic() - started) * 1000), "log": "launcher.log"}


def validate_result(output, scenario):
    result = read_object(output / "results/result.json", "Client result")
    if (result.get("evaluation_version") != scenario["evaluation_version"]
            or result.get("case_id") != scenario["case_id"]):
        raise PreflightError("Client result has the wrong evaluation version or case ID")
    status = result.get("status")
    if status not in {"success", "failed", "budget_exhausted", "infra_error", "cancelled"}:
        raise PreflightError("Client result has an invalid status")
    if not isinstance(result.get("reason"), str) or not result["reason"]:
        raise PreflightError("Client result needs a reason")
    number(result.get("elapsed_ms"), "result.elapsed_ms")
    for key in ("model_calls", "denied_model_calls", "usage_reports", "unknown_usage_reports"):
        number(result.get(key), "result." + key, integer=True)
    if not isinstance(result.get("tokens"), dict):
        raise PreflightError("Client result needs token telemetry")
    for key in ("input", "output", "cache_read", "cache_write", "total"):
        number(result["tokens"].get(key), "result.tokens." + key, integer=True)
    if type(result.get("usage_complete")) is not bool:
        raise PreflightError("Client result must report whether token usage is complete")
    if not isinstance(result.get("milestones"), list):
        raise PreflightError("Client result needs milestone evidence")
    if status in {"success", "failed", "budget_exhausted"}:
        if scenario["evaluation_version"] == "dragon-combat-v1":
            for key in ("initial_snapshot", "final_snapshot"):
                if not isinstance(result.get(key), dict) or not result[key]:
                    raise PreflightError("Scored result needs initial and final world snapshots")
            dragon_combat_result_evidence(result, scenario)
        conditions = [scenario["success"], *(milestone["condition"] for milestone in scenario.get("milestones", []))]
        for key in ("initial_snapshot", "final_snapshot"):
            if scenario["evaluation_version"] == "dragon-combat-v1":
                continue
            if not isinstance(result.get(key), dict) or not result[key]:
                raise PreflightError("Scored result needs initial and final world snapshots")
            snapshot = result[key]
            initial_deaths = result["initial_snapshot"].get("deaths")
            deaths = snapshot.get("deaths")
            died = (snapshot.get("alive") is False or
                    (type(initial_deaths) is int and type(deaths) is int and 0 <= initial_deaths < deaths))
            # The dead body may already be removed. Death remains a scored failure
            # even when no body-relative observer evidence can be collected.
            if not (status == "failed" and result["reason"] == "companion_died" and died):
                world_evidence(snapshot, conditions, key)
                if scenario["evaluation_version"] == "end-resources-v1":
                    world = snapshot.get("world")
                    if not isinstance(world, dict):
                        raise PreflightError(f"{key}.world must contain required observer evidence")
                    number(world.get("loadout_held_ticks"), key + ".world.loadout_held_ticks", integer=True)
                if scenario["evaluation_version"] == "end-entry-v1":
                    end_entry_evidence(snapshot, key)
                    if snapshot.get("companion_uuid") != result["initial_snapshot"].get("companion_uuid"):
                        raise PreflightError("End-entry snapshots must track the same companion UUID")
        if status == "success":
            world_evidence(result["final_snapshot"], [scenario["success"]], "final_snapshot", require_met=True)
            if "success_hold_ticks" in scenario:
                number(result.get("success_held_ticks"), "result.success_held_ticks",
                       minimum=scenario["success_hold_ticks"], integer=True)
            if scenario["evaluation_version"] == "end-resources-v1":
                number(result["final_snapshot"]["world"].get("loadout_held_ticks"),
                       "final_snapshot.world.loadout_held_ticks",
                       minimum=scenario.get("success_hold_ticks", 0), integer=True)
            if scenario["evaluation_version"] == "end-entry-v1":
                final = result["final_snapshot"]
                number(final["world"].get("end_alive_held_ticks"), "final_snapshot.world.end_alive_held_ticks",
                       minimum=scenario.get("success_hold_ticks", 0), integer=True)
                if final["world"].get("native_end_entry") is not True:
                    raise PreflightError("Successful end-entry result needs the complete native entrance chain")
                if final.get("deaths") != result["initial_snapshot"].get("deaths"):
                    raise PreflightError("Successful end-entry result cannot follow a death")
                for field, criterion_key in (("health", "min_health"), ("food", "min_food")):
                    if criterion_key in scenario["success"]:
                        number(final.get(field), "final_snapshot." + field,
                               minimum=scenario["success"][criterion_key])
                for field in ("arrival", "activation", "entry"):
                    if (final["world"]["evidence"]["end_entry"][field]["game_tick"]
                            < result["initial_snapshot"]["game_tick"]):
                        raise PreflightError("Native entrance milestones must belong to this run")
    if result.get("spec") != scenario:
        raise PreflightError("Client result does not match the exact requested scenario and budget")
    trace_path = output / "results/trace.json"
    try:
        trace = json.loads(trace_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise PreflightError("Client trace is missing, unreadable, or invalid JSON") from None
    if not isinstance(trace, list) or not trace:
        raise PreflightError("Client trace must contain evaluation events")
    return result


def parser_for():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", type=Path, default=SCENARIOS / "craft-planks.json")
    parser.add_argument("--providers", type=Path, help="Existing private config/numen/providers.json")
    parser.add_argument("--provider-id", help="Entry id within the provider library (not the provider name)")
    parser.add_argument("--max-model-calls", type=int)
    parser.add_argument("--max-seconds", type=int, help="Task wall-clock budget after world setup")
    parser.add_argument("--max-tokens", type=int, help="Observed input+output tokens; a final call may overshoot")
    parser.add_argument("--startup-seconds", type=int, default=600, help="Extra client/world startup allowance")
    parser.add_argument("--prepare-only", action="store_true", help="Write inputs and blockers; never launch or call a model")
    parser.add_argument("--output", type=Path, help="New directory strictly below build/evals/live-v1")
    parser.add_argument("--offline", action="store_true", help="Gradle cache only; model requests STILL use the network")
    parser.add_argument("--neoforge-version", help="Explicit environment override, without editing project configuration")
    return parser


def main(argv=None):
    args = parser_for().parse_args(argv)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + uuid.uuid4().hex[:8]
    output = (args.output or RUNS / run_id).resolve()
    if not output.is_relative_to(RUNS.resolve()) or output == RUNS.resolve():
        print("Refusing output outside the private build/evals/live-v1 run directory")
        return 2
    try:
        output.mkdir(parents=True, exist_ok=False)
    except OSError:
        print("Output directory must be new and writable")
        return 2
    (output / "results").mkdir()
    metadata = {"evaluation_version": VERSION, "run_id": run_id, "split": "dev", "rep": 0,
                "started_at_utc": datetime.now(timezone.utc).isoformat(), "status": "preparing",
                "success": None, "model_cost": None, "platform": platform.platform(),
                "python": platform.python_version(), "offline_gradle": args.offline,
                "neoforge_version_override": args.neoforge_version, "command": None}
    secrets = []
    try:
        scenario = validate_scenario(read_object(args.scenario.resolve(), "Scenario"))
        metadata["evaluation_version"] = scenario["evaluation_version"]
        budget = {"max_model_calls": args.max_model_calls, "max_seconds": args.max_seconds,
                  "max_tokens": args.max_tokens}
        blockers = []
        for key, value in budget.items():
            if value is None:
                blockers.append(f"Explicit --{key.replace('_', '-')} is required before launch")
            else:
                number(value, key, minimum=1, integer=True)
        number(args.startup_seconds, "startup_seconds", minimum=1, integer=True)
        if bool(args.providers) != bool(args.provider_id):
            raise PreflightError("Supply both --providers and --provider-id")
        entry = select_provider(args.providers.resolve(), args.provider_id) if args.providers else None
        if entry is None:
            blockers.append("Provider library and entry ID are required before launch")
        else:
            secrets = [entry.get("api_key") or ""]
            if not (entry.get("api_key") or "").strip():
                blockers.append("Selected provider entry needs a nonempty api_key before launch")
            metadata["provider"] = {key: entry.get(key) for key in ("id", "provider", "model", "reasoning_effort", "ctx")}
        command = command_for(output, args.offline, args.neoforge_version)
        evaluation_files = [args.scenario.resolve(), Path(__file__).resolve(),
                            ROOT / "tools/eval_common.py",
                            ROOT / "tools/calibrate_live_eval.py",
                            *ROOT.glob("api/common/src/client/java/**/LiveEval*.java"),
                            ROOT / "core/neoforge/build.gradle"]
        # Reuse the repository benchmark's source manifest, which includes new
        # untracked source files but excludes runtime configuration and keys.
        source_files = source_files_for([p for p in evaluation_files if p.is_relative_to(ROOT)])
        source_hash = digest_files(source_files)
        metadata.update(case_id=scenario["case_id"], budget=budget, blockers=blockers,
                        candidate=git("rev-parse", "HEAD")[:12] + "+" + source_hash[:12],
                        source_sha256=source_hash, git_status=git("status", "--short"),
                        scenario_sha256=hashlib.sha256(args.scenario.read_bytes()).hexdigest(),
                        startup_seconds=args.startup_seconds, planned_command=command)
        write_json(output / "source-hashes.json", {
            p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files})
        source_names = {p.relative_to(ROOT).as_posix() for p in source_files}
        changed = [name for name in git("diff", "--name-only", "HEAD").splitlines() if name in source_names]
        (output / "candidate.patch").write_bytes(candidate_patch(changed))
        scenario["budget"] = budget
        request = {"scenario": scenario, "provider_id": args.provider_id,
                   "output_dir": str(output / "results"), "world_name": "numen-eval-" + uuid.uuid4().hex}
        write_json(output / "request.json", request)
        if blockers and not args.prepare_only:
            raise PreflightError("; ".join(blockers))
        if entry is not None:
            config = output / "game/config/numen"
            config.mkdir(parents=True)
            write_json(config / "providers.json", {"entries": [entry]})
        else:
            (output / "game").mkdir()
        if args.prepare_only:
            metadata.update(status="prepared", reason="No client launched; no model calls or capability score")
            return_code = 0
        else:
            metadata.update(status="launching")
            write_json(output / "run.json", metadata)
            print(f"Launching real Minecraft client; records: {output}", flush=True)
            execution = launch(command, output, args.startup_seconds + args.max_seconds, secrets)
            metadata["command"] = execution
            metadata["companion_chat"] = capture_companion_chat(output, secrets)
            if execution["timeout"] or execution["interrupted"] or execution["exit_code"] != 0:
                raise PreflightError("Client launch did not complete normally; inspect launcher.log and partial results")
            result = validate_result(output, scenario)
            if source_hash != digest_files(source_files_for([p for p in evaluation_files if p.is_relative_to(ROOT)])):
                raise PreflightError("Source changed during the run; freeze the working tree and rerun")
            scored = result["status"] in {"success", "failed", "budget_exhausted"}
            metadata.update(status=result["status"], success=(result["status"] == "success") if scored else None,
                            reason=result["reason"],
                            result_ref="results/result.json")
            return_code = 0 if scored else 2
    except (PreflightError, OSError, subprocess.SubprocessError) as error:
        # Provider parsing errors deliberately omit source text. Transport logs
        # are redacted before writing; never print the provider entry itself.
        metadata.update(status="infra_error", success=None,
                        reason=redact(str(error), secrets))
        return_code = 2
    metadata["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
    write_json(output / "run.json", metadata)
    print(f"{metadata['status']}: {redact(metadata.get('reason', ''), secrets)}")
    print(f"Records: {output}")
    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
