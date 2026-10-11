"""Launch isolated Minecraft/model trials; --prepare-only never invokes Gradle or a model."""
import argparse
import hashlib
import json
import os
import signal
from pathlib import Path
import subprocess
import time
import uuid
import threading
from urllib.parse import urlsplit
import paired_report

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = ("collect", "structure", "furnace")
SEED = 741103
FIXTURE = "vanilla-paired-v1"


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def capture_log(stream, path, secrets, errors):
    """Redact before persistence, including secrets split across pipe reads."""
    needles = sorted({s.encode("utf-8") for s in secrets if s}, key=len, reverse=True)
    width = max(map(len, needles), default=1)
    pending = b""
    try:
        with path.open("wb") as log:
            while True:
                chunk = stream.read1(4096) if hasattr(stream, "read1") else stream.read(4096)
                pending += chunk
                safe = len(pending) if not chunk else max(0, len(pending) - width + 1)
                pos = 0
                output = bytearray()
                while pos < safe:
                    match = next((key for key in needles if pending.startswith(key, pos)), None)
                    if match:
                        output.extend(b"[redacted]")
                        pos += len(match)
                    else:
                        output.append(pending[pos])
                        pos += 1
                log.write(output)
                log.flush()
                pending = pending[pos:]
                if not chunk:
                    break
    except (OSError, ValueError):
        errors.append("launcher_log_capture_failed")
    finally:
        stream.close()


def fingerprint():
    diff = subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=ROOT)
    digest = hashlib.sha256(diff)
    # git diff omits new files, including the experiment itself.
    paths = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=ROOT)
    for name in sorted(paths.split(b"\0")):
        if name:
            digest.update(name + b"\0")
            digest.update((ROOT / os.fsdecode(name)).read_bytes())
    return {"git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip(),
             "working_diff_sha256": digest.hexdigest()}


def export_report(output, manifest, slots, began):
    contract = {key: manifest[key] for key in paired_report.MANIFEST_KEYS if key != "wall_time_ms"}
    contract["wall_time_ms"] = int((time.monotonic() - began) * 1000)
    records = [contract, *slots]
    for row in slots:
        trial = output / f"{row['case_id']}-{row['rep']}-{'on' if row['variant'] == 'verify_on' else 'off'}"
        if trial.exists():
            save(trial / "report-result.json", row)
    (output / "paired.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n" for row in records), encoding="utf-8")
    try:
        report = paired_report.build_report(records)
    except paired_report.ValidationError as error:
        (output / "summary.json").unlink(missing_ok=True)
        save(output / "statistics_boundary.json", {"valid": False, "reason": str(error),
             "unknown_usage_slots": [i for i, row in enumerate(slots) if row["input_tokens"] is None or row["output_tokens"] is None]})
        return False
    save(output / "statistics_boundary.json", {"valid": True})
    save(output / "summary.json", report)
    return True


def normalize_result(slot, raw, elapsed):
    row = dict(slot)
    known = raw.get("usage_complete") is True
    usage = raw.get("usage")
    if known and isinstance(usage, dict) and all(type(usage.get(k)) is int and usage[k] >= 0
            for k in ("input", "output", "cacheRead", "cacheWrite")):
        row["input_tokens"] = usage["input"] + usage["cacheRead"] + usage["cacheWrite"]
        row["output_tokens"] = usage["output"]
    else:
        row["input_tokens"] = row["output_tokens"] = None
        known = False
    row.update(elapsed_ms=elapsed, llm_calls=raw.get("calls"), tool_calls=raw.get("tools"))
    judged = type(raw.get("success")) is bool and raw.get("kind") != "harness_error"
    if not known or not judged:
        row.update(status="infra_error", reason=raw.get("kind", "usage_unknown") if not judged else "usage_unknown", judge_passed=None)
    else:
        row.update(status="success" if raw["success"] else "failure",
                   reason=None if raw["success"] else raw.get("kind", "goal_not_met"), judge_passed=raw["success"])
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--providers-file", required=True, type=Path)
    parser.add_argument("--provider-id", required=True)
    parser.add_argument("--output", required=True, type=Path, help="New directory, outside the repository")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--trial-limit", type=int, default=18, help="Diagnostic first N trials (1..18); other slots remain not_run")
    args = parser.parse_args()
    if not 1 <= args.trial_limit <= 18:
        parser.error("trial-limit must be between 1 and 18")
    providers = args.providers_file.resolve(strict=True)
    library = json.loads(providers.read_text(encoding="utf-8-sig"))
    entry = next((e for e in library["entries"] if e["id"] == args.provider_id), None)
    secrets = [e.get("api_key") for e in library["entries"] if e.get("api_key")]
    if not entry or not entry.get("api_key") or not entry.get("model"):
        parser.error("selected existing provider entry requires api_key and model")
    # Product transport logs endpoint URLs. Credentials must stay in auth headers.
    for key in ("base_url", "proxy"):
        url = urlsplit(entry.get(key) or "")
        if url.username or url.password or url.query or url.fragment:
            parser.error(f"{key} must not contain URL credentials, query, or fragment")
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents or output.exists():
        parser.error("output must be a new directory outside the repository")
    stamp = fingerprint()
    output.mkdir(parents=True)
    manifest = {**stamp, "fixture_version": FIXTURE, "seed": SEED, "planned_denominator": 18,
                "provider": entry.get("provider"), "model": entry["model"], "repeats": 3,
                "deadline_seconds": 600, "max_calls": 48, "max_tools": 96,
                 "feedback_protocol": "final-reply continuation; on includes server differences, off omits them"}
    manifest["trial_limit"] = args.trial_limit
    manifest["diagnostic_run"] = args.trial_limit < 18
    settings = {k: entry.get(k) for k in ("provider", "model", "base_url", "proxy", "reasoning_effort", "ctx")}
    config_hash = hashlib.sha256(providers.read_bytes()).hexdigest()
    manifest.update(type="manifest", schema_version=1, run_id=uuid.uuid4().hex,
                    revision=stamp["git_head"] + ":" + stamp["working_diff_sha256"], wall_time_ms=0,
                    candidate={"provider": entry["provider"], "model": entry["model"],
                               "settings_id": hashlib.sha256(json.dumps(settings, sort_keys=True).encode()).hexdigest()},
                    cases=[{"case_id": s, "fixture": FIXTURE + "/" + s, "seeds": [SEED] * 3} for s in SCENARIOS],
                    intervention={"id": "server_diff_after_final_reply_v1", "product_goal_steward_ablation": False,
                                  "verify_on": "same continuation plus server expected/actual differences",
                                  "verify_off": "same continuation without server differences"})
    save(output / "manifest.json", manifest)
    commands = []
    results = []
    slots = []
    began_suite = time.monotonic()
    for scenario in SCENARIOS:
        for rep in range(1, 4):
            for variant in paired_report.VARIANTS:
                slots.append({**{k: manifest[k] for k in paired_report.IDENTITY}, "type": "result",
                    "case_id": scenario, "fixture": FIXTURE + "/" + scenario, "rep": rep, "seed": SEED,
                    "variant": variant, "status": "not_run", "reason": "prepared_only" if args.prepare_only else "not_started",
                    "elapsed_ms": None, "judge_passed": None, **{k: 0 for k in paired_report.COUNTERS}})
    export_report(output, manifest, slots, began_suite)
    blocked = None
    attempted = 0
    for scenario in SCENARIOS:
        for repeat in range(1, 4):
            # Alternate pair order to reduce a fixed cache/order advantage.
            for feedback in ((False, True) if repeat % 2 else (True, False)):
                trial = output / f"{scenario}-{repeat}-{'on' if feedback else 'off'}"
                game = trial / "game"
                game.mkdir(parents=True)
                save(trial / "request.json", {**manifest, "scenario": scenario, "repeat": repeat,
                     "feedback": feedback, "output": str(trial), "providers_file": str(providers),
                     "provider_id": args.provider_id})
                (game / "eula.txt").write_text("eula=true\n", encoding="utf-8")
                (game / "server.properties").write_text(
                    f"level-name=experiment_world\nlevel-seed={SEED}\nlevel-type=minecraft:flat\n"
                    "online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\n"
                    "spawn-protection=0\nview-distance=4\nsimulation-distance=4\n"
                    "max-tick-time=60000\n", encoding="utf-8")
                command = [str(ROOT / "gradlew.bat" if os.name == "nt" else ROOT / "gradlew"),
                           ":core:neoforge:runPairedExperiment", "--no-daemon", "-PpairedExperiment",
                           f"-PexperimentDirectory={game}", f"-PexperimentRequest={trial / 'request.json'}"]
                commands.append(command)
                if args.prepare_only:
                    continue
                slot_index = next(i for i, row in enumerate(slots) if row["case_id"] == scenario and row["rep"] == repeat
                                  and row["variant"] == ("verify_on" if feedback else "verify_off"))
                if attempted >= args.trial_limit:
                    slots[slot_index]["reason"] = "diagnostic_trial_limit"
                    export_report(output, manifest, slots, began_suite)
                    continue
                if fingerprint() != stamp or hashlib.sha256(providers.read_bytes()).hexdigest() != config_hash:
                    blocked = "source_or_configuration_changed"
                if blocked:
                    slots[slot_index]["reason"] = blocked
                    export_report(output, manifest, slots, began_suite)
                    continue
                began = time.monotonic()
                attempted += 1
                try:
                    process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                               start_new_session=os.name != "nt")
                except OSError as error:
                    (trial / "launcher.log").write_text(f"Launcher could not start process: {type(error).__name__}; errno={error.errno}\n", encoding="utf-8")
                    save(trial / "launcher-status.json", {"started": False, "exit_code": None, "reason": "launcher_not_started"})
                    slots[slot_index]["reason"] = "launcher_not_started"
                    export_report(output, manifest, slots, began_suite)
                    continue
                log_errors = []
                reader = threading.Thread(target=capture_log, args=(process.stdout, trial / "launcher.log", secrets, log_errors), daemon=True)
                reader.start()
                watchdog = False
                try:
                    process.wait(timeout=900)  # Includes bounded startup/build allowance.
                except subprocess.TimeoutExpired:
                    watchdog = True
                    if os.name == "nt":
                        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True)
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                reader.join(timeout=10)
                if reader.is_alive():
                    log_errors.append("launcher_log_pipe_not_closed")
                result_file = trial / "result.json"
                missing_result = False
                try:
                    result = json.loads(result_file.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    missing_result = True
                    try:
                        result = json.loads((trial / "accounting.json").read_text(encoding="utf-8"))
                    except (OSError, ValueError):
                        result = {"usage_complete": False, "calls": None, "tools": None, "usage": None}
                    result["kind"] = "watchdog_no_server_result" if watchdog else "process_exit_no_server_result"
                evidence = {"started": True, "exit_code": process.returncode, "watchdog": watchdog,
                            "server_result_missing_or_invalid": missing_result, "log_errors": log_errors}
                save(trial / "launcher-status.json", evidence)
                slots[slot_index] = normalize_result(slots[slot_index], result, int((time.monotonic() - began) * 1000))
                if process.returncode != 0 or watchdog or log_errors:
                    slots[slot_index].update(status="infra_error", judge_passed=None,
                        reason=result["kind"] if missing_result else "launcher_watchdog" if watchdog else
                        "launcher_nonzero_exit" if process.returncode != 0 else "launcher_log_capture_failed")
                if fingerprint() != stamp or hashlib.sha256(providers.read_bytes()).hexdigest() != config_hash:
                    blocked = "source_or_configuration_changed"
                    slots[slot_index].update(status="infra_error", reason=blocked, judge_passed=None)
                if not result_file.exists():
                    save(result_file, result)
                results.append({"scenario": scenario, "repeat": repeat, "feedback": feedback, **result})
                save(output / "results.json", results)
                export_report(output, manifest, slots, began_suite)
    save(output / "commands.json", commands)
    manifest["wall_time_ms"] = int((time.monotonic() - began_suite) * 1000)
    save(output / "manifest.json", manifest)
    export_report(output, manifest, slots, began_suite)
    print(f"{'Prepared' if args.prepare_only else 'Finished'} 18 trials: {output}")


if __name__ == "__main__":
    main()
