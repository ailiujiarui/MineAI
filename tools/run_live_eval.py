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

from run_bot_eval import digest_files, git, source_files_for, write_json


ROOT = Path(__file__).resolve().parents[1]
VERSION = "live-v1"
SUPPORTED_VERSIONS = {VERSION, "survival-v1"}
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


def validate_scenario(value):
    if set(value) - {"evaluation_version", "case_id", "split", "source", "prompt", "seed",
                     "world_preset", "setup", "success", "milestones", "budget",
                     "world_observer", "success_hold_ticks"}:
        raise PreflightError("Scenario contains unsupported fields")
    if (not isinstance(value.get("evaluation_version"), str)
            or value["evaluation_version"] not in SUPPORTED_VERSIONS):
        raise PreflightError("Scenario has the wrong evaluation_version")
    if "world_observer" in value and value["world_observer"] != "sustainable-survival-v1":
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
    if not isinstance(setup, dict) or set(setup) - {"spawn", "floor", "blocks", "inventory"}:
        raise PreflightError("Unsupported setup fields")
    if "spawn" in setup:
        position(setup["spawn"], "setup.spawn")
    if "inventory" in setup:
        item_counts(setup["inventory"], "setup.inventory")
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
        conditions = [scenario["success"], *(milestone["condition"] for milestone in scenario.get("milestones", []))]
        for key in ("initial_snapshot", "final_snapshot"):
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
        if status == "success":
            world_evidence(result["final_snapshot"], [scenario["success"]], "final_snapshot", require_met=True)
            if "success_hold_ticks" in scenario:
                number(result.get("success_held_ticks"), "result.success_held_ticks",
                       minimum=scenario["success_hold_ticks"], integer=True)
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
                            ROOT / "tools/run_bot_eval.py",
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
