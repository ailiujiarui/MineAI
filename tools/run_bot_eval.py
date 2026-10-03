"""Run the versioned bot benchmark against the repository's real Gradle entry points."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "evals/bot-v1/cases.json"


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def digest_files(paths):
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def source_files_for(evaluation_files):
    """Include untracked source additions because Gradle compiles them too."""
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT).decode("utf-8").split("\0")
    source_files = {ROOT / name for name in names if name and
                    (("/src/" in name and not name.startswith("docs/")) or
                     name.endswith((".gradle", ".properties", ".snbt"))) and (ROOT / name).is_file()}
    source_files.update(evaluation_files)
    return sorted(source_files)


def collect(cases, raw, selected, evaluation_version="bot-v1"):
    """Fail closed on missing, duplicate, malformed or unexpected observations."""
    rows = {}
    errors = []
    known = {case["case_id"]: case for case in cases}
    for path in sorted(raw.glob("*.jsonl")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            try:
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError("observation must be a JSON object")
                case_id = row["case_id"]
                if case_id not in known or known[case_id]["layer"] not in selected:
                    raise ValueError(f"unexpected case {case_id}")
                if case_id in rows:
                    raise ValueError(f"duplicate case {case_id}")
                if row.get("evaluation_version") != evaluation_version:
                    raise ValueError(f"wrong or missing evaluation_version for {case_id}")
                if row.get("status") not in {"ok", "system_failure", "infra_error", "grading_error"}:
                    raise ValueError(f"invalid status for {case_id}")
                if row["status"] in {"ok", "system_failure"} and type(row.get("success")) is not bool:
                    raise ValueError(f"missing boolean success for {case_id}")
                if row["status"] == "system_failure" and row["success"]:
                    raise ValueError(f"system_failure cannot succeed for {case_id}")
                if (not isinstance(row.get("metrics"), dict)
                        or not isinstance(row.get("reason"), str) or not row["reason"].strip()):
                    raise ValueError(f"missing metrics/reason for {case_id}")
                if not isinstance(row.get("evidence"), dict) or not row["evidence"]:
                    raise ValueError(f"missing evidence for {case_id}")
                if row.get("layer") != known[case_id]["layer"]:
                    raise ValueError(f"wrong layer for {case_id}")
                row["output_ref"] = f"raw/{path.name}:{number}"
                rows[case_id] = row
            except (ValueError, KeyError, TypeError) as error:
                errors.append(f"{path.name}:{number}: {error}")
    result = []
    for case in cases:
        row = rows.get(case["case_id"])
        if row is None:
            selected_case = case["layer"] in selected
            row = {"case_id": case["case_id"], "layer": case["layer"],
                   "status": "infra_error" if selected_case else "not_run", "success": None,
                   "metrics": {}, "reason": "No observation produced; see command log" if selected_case
                   else case.get("blocked_by", "Layer not selected"), "output_ref": None}
        row.update(split=case["split"], group=case["group"], rep=0, cost=None,
                   live_model_latency_ms=None, first_correct_reply_ms=None)
        result.append(row)
    return result, errors


def summarize(rows, errors, commands):
    layers = {}
    for layer in sorted({row["layer"] for row in rows}):
        subset = [row for row in rows if row["layer"] == layer]
        scored = [row for row in subset if row["status"] in {"ok", "system_failure"}]
        passed = sum(row["success"] is True for row in scored)
        invalid = sum(row["status"] in {"infra_error", "grading_error"} for row in subset)
        layers[layer] = {"passed": passed, "scored": len(scored), "failed": len(scored) - passed,
                         "invalid": invalid, "not_run": sum(row["status"] == "not_run" for row in subset),
                         "observed_success_rate": passed / len(scored) if scored and not invalid else None}
    valid = not errors and not any(layer["invalid"] for layer in layers.values())
    valid = valid and all(command["exit_code"] == 0 for command in commands)
    return {"valid_run": valid, "layers": layers, "record_errors": errors,
            "interpretation": "Exploratory dev evidence only. Layers are not pooled. No live LLM calls.",
            "external_model_calls": 0, "external_model_tokens": 0, "external_model_cost": 0,
            "total_cost": None}


def run_command(task, output, offline, timeout, neoforge_version=None):
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), task, "--no-daemon", "--console=plain",
               f"-PnumenEvalOutput={output / 'raw'}"]
    if offline:
        command.append("--offline")
    if neoforge_version:
        command.append(f"-Pneoforge_version={neoforge_version}")
    log_path = output / ("agent.log" if task == ":agent:benchmark" else "gametest.log")
    started = time.monotonic()
    print(f"Running {task}; log: {log_path}", flush=True)
    # Each command owns its process group. Timeout stops its server/JVM as well as the wrapper.
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
                                   start_new_session=os.name != "nt")
        timed_out = False
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                               stdout=log, stderr=subprocess.STDOUT, check=False)
            else:
                import signal
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            code = 124
    print(f"{task}: exit {code}, {time.monotonic() - started:.1f}s", flush=True)
    return {"argv": command, "exit_code": code, "timeout": timed_out,
            "duration_ms": round((time.monotonic() - started) * 1000), "log": log_path.name}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--layer", choices=["jvm", "body", "all"], default="jvm")
    parser.add_argument("--output", type=Path, help="New directory; existing paths are rejected")
    parser.add_argument("--offline", action="store_true", help="Use cached Gradle dependencies only")
    parser.add_argument("--neoforge-version", help="Explicit alternate environment; does not edit project configuration")
    args = parser.parse_args()
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    started_at = datetime.now(timezone.utc).isoformat()
    output = (args.output or ROOT / "build/evals/bot-v1" /
              datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")).resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / "raw").mkdir()
    selected = ({"planning", "response"} if args.layer == "jvm" else {"body"}
                if args.layer == "body" else {"planning", "response", "body"})
    evaluation_files = [SPEC, Path(__file__), ROOT / "agent/build.gradle", ROOT / "core/neoforge/build.gradle",
                        *ROOT.glob("agent/src/test/java/**/*Benchmark.java"),
                        ROOT / "agent/src/test/java/com/dwinovo/numen/agent/loop/LoopHarness.java",
                        ROOT / "core/neoforge/src/main/java/com/dwinovo/numen/core/gametest/BenchmarkGameTests.java",
                        ROOT / "core/neoforge/src/main/java/com/dwinovo/numen/core/gametest/GameTestKit.java"]
    source_files = source_files_for(evaluation_files)
    evaluation_hash = digest_files(evaluation_files)
    source_hash = digest_files(source_files)
    revision = git("rev-parse", "HEAD")
    candidate = f"{revision[:12]}+{source_hash[:12]}"
    metadata = {"evaluation_version": spec["evaluation_version"], "evaluation_sha256": evaluation_hash,
                "candidate": candidate, "git_revision": revision, "source_sha256": source_hash,
                "git_status": git("status", "--short"), "started_at_utc": started_at,
                "platform": platform.platform(), "python": platform.python_version(),
                "java_launcher": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.strip(),
                "selected_layers": sorted(selected), "repetitions": 1, "concurrency": 1,
                "neoforge_version_override": args.neoforge_version,
                "external_model_call_budget": 0, "commands": []}
    environment = {key: metadata[key] for key in
                   ("platform", "python", "java_launcher", "neoforge_version_override", "concurrency")}
    environment["project_properties"] = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    environment_hash = hashlib.sha256(json.dumps(environment, sort_keys=True).encode()).hexdigest()
    metadata["environment_sha256"] = environment_hash
    write_json(output / "run.json", metadata)
    # Retain exact evaluator/data and changed-source hashes without copying credentials or runtime configs.
    write_json(output / "manifest.json", spec)
    source_names = {p.relative_to(ROOT).as_posix() for p in source_files}
    changed = [name for name in git("diff", "--name-only", "HEAD").splitlines() if name in source_names]
    (output / "candidate.patch").write_bytes(subprocess.check_output(
        ["git", "diff", "--binary", "HEAD", "--", *changed], cwd=ROOT) if changed else b"")
    write_json(output / "source-hashes.json", {
        p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(source_files)})
    for task, timeout in [(":agent:benchmark", 600), (":core:neoforge:runGameTestServer", 1800)]:
        if (task == ":agent:benchmark" and "planning" in selected or
                task != ":agent:benchmark" and "body" in selected):
            try:
                metadata["commands"].append(run_command(task, output, args.offline, timeout, args.neoforge_version))
            except OSError as error:
                metadata["commands"].append({"argv": [task], "exit_code": -1, "error": str(error)})
            write_json(output / "run.json", metadata)
    rows, errors = collect(spec["cases"], output / "raw", selected, spec["evaluation_version"])
    for row in rows:
        row.update(candidate=candidate, evaluation_version=spec["evaluation_version"],
                   evaluation_sha256=evaluation_hash, environment_sha256=environment_hash)
    if source_hash != digest_files(source_files_for(evaluation_files)):
        errors.append("Source changed during run; rerun against a frozen working tree")
    summary = summarize(rows, errors, metadata["commands"])
    summary.update(candidate=candidate, evaluation_version=spec["evaluation_version"],
                   evaluation_sha256=evaluation_hash, environment_sha256=environment_hash)
    (output / "results.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    write_json(output / "summary.json", summary)
    metadata["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
    write_json(output / "run.json", metadata)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"Results: {output}")
    return 0 if summary["valid_run"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
