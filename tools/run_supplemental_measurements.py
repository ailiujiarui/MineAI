"""Launch isolated runtime/restart/permission measurements with bounded processes."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
import time
from urllib.parse import urlsplit

from run_paired_experiment import ROOT, capture_log, fingerprint, save

PROVIDER_ID = "prov_1a0dc51e18f_0"
PROVIDERS = ROOT / "core/neoforge/runs/client/config/numen/providers.json"
SEED = 741103


def command_for(game, request):
    return [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")),
            ":core:neoforge:runSupplementalMeasurements", "--no-daemon",
            "-PsupplementalMeasurements", f"-PmeasurementDirectory={game}",
            f"-PmeasurementRequest={request}"]


def prepare_game(game):
    game.mkdir(parents=True)
    (game / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (game / "server.properties").write_text(
        f"level-name=measurement_world\nlevel-seed={SEED}\nlevel-type=minecraft:flat\n"
        "online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\n"
        "spawn-protection=0\nview-distance=4\nsimulation-distance=4\n"
        "max-tick-time=60000\n", encoding="utf-8")


def redact_tree_logs(game, secrets):
    # Minecraft also writes its own logs, outside the captured process pipe.
    # Redact them after exit without dropping diagnostic lines or failure files.
    for folder in (game / "logs", game / "crash-reports"):
        if not folder.exists():
            continue
        for path in folder.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix == ".gz":
                import gzip
                data = gzip.decompress(path.read_bytes())
                for secret in secrets:
                    data = data.replace(secret.encode(), b"[redacted]")
                path.write_bytes(gzip.compress(data))
            else:
                data = path.read_bytes()
                for secret in secrets:
                    data = data.replace(secret.encode(), b"[redacted]")
                path.write_bytes(data)


def launch(command, phase_output, game, secrets, watchdog_seconds, result_file=None):
    began = time.monotonic()
    errors = []
    evidence = {"started": False, "exit_code": None, "watchdog": False}
    try:
        process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, start_new_session=os.name != "nt")
    except OSError as error:
        evidence["reason"] = "launcher_not_started"
        evidence["error_type"] = type(error).__name__
        evidence["errno"] = error.errno
        save(phase_output / "launcher-status.json", evidence)
        return evidence
    evidence["started"] = True
    reader = threading.Thread(target=capture_log,
                              args=(process.stdout, phase_output / "launcher.log", secrets, errors), daemon=True)
    reader.start()
    try:
        process.wait(timeout=watchdog_seconds)
    except subprocess.TimeoutExpired:
        evidence["watchdog"] = True
        if os.name == "nt":
            killed = subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True)
            if killed.returncode:
                errors.append("launcher_process_tree_kill_failed")
        else:
            os.killpg(process.pid, signal.SIGKILL)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            errors.append("launcher_process_did_not_exit_after_kill")
    reader.join(timeout=10)
    if reader.is_alive():
        errors.append("launcher_log_pipe_not_closed")
    try:
        redact_tree_logs(game, secrets)
    except (OSError, ValueError, EOFError):
        errors.append("game_log_redaction_failed")
    evidence.update(exit_code=process.returncode, elapsed_ms=int((time.monotonic() - began) * 1000), log_errors=errors)
    result_file = result_file or phase_output / "result.json"
    try:
        result = json.loads(result_file.read_text(encoding="utf-8"))
        evidence["server_result"] = result
        evidence["server_result_missing_or_invalid"] = False
    except (OSError, ValueError):
        evidence["server_result_missing_or_invalid"] = True
    # The server's raw result is retained, including failed observations; launcher
    # failures never replace it with a fabricated success/failure result.
    evidence["infrastructure_ok"] = (process.returncode == 0 and not evidence["watchdog"]
                                     and not errors and not evidence["server_result_missing_or_invalid"])
    save(phase_output / "launcher-status.json", evidence)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("all", "runtime", "restart", "permission"), default="all")
    parser.add_argument("--providers-file", type=Path, default=PROVIDERS)
    parser.add_argument("--provider-id", default=PROVIDER_ID)
    parser.add_argument("--output", required=True, type=Path, help="New directory outside repository")
    parser.add_argument("--restart-repeats", type=int, default=5)
    parser.add_argument("--prepare-only", action="store_true", help="Write requests/commands without launching Gradle or models")
    parser.add_argument("--watchdog-seconds", type=int, default=1200)
    args = parser.parse_args()
    if args.restart_repeats < 5:
        parser.error("restart-repeats must be at least 5")
    if not 900 <= args.watchdog_seconds <= 3600:
        parser.error("watchdog-seconds must be between 900 and 3600")
    providers = args.providers_file.resolve(strict=True)
    library = json.loads(providers.read_text(encoding="utf-8-sig"))
    entry = next((e for e in library["entries"] if e["id"] == args.provider_id), None)
    if not entry or not entry.get("api_key") or not entry.get("model"):
        parser.error("existing provider entry requires api_key and model")
    for name in ("base_url", "proxy"):
        url = urlsplit(entry.get(name) or "")
        if url.username or url.password or url.query or url.fragment:
            parser.error(f"{name} must not contain URL credentials/query/fragment")
    secrets = [e["api_key"] for e in library["entries"] if e.get("api_key")]
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error("output must be a new directory outside repository")
    output.mkdir(parents=True)
    manifest = {**fingerprint(), "seed": SEED, "provider": entry.get("provider"), "model": entry["model"],
                "provider_id": args.provider_id, "runtime_interrupt_repeats": 10, "runtime_mspt_pairs": 3,
                "phase_ticks": 250, "runtime_max_wire_attempts": 3, "restart_repeats": args.restart_repeats,
                "permission_repeats": 10, "launcher_watchdog_seconds": args.watchdog_seconds,
                "fixture": "isolated-supplemental-v1", "prepared_only": args.prepare_only}
    save(output / "manifest.json", manifest)
    commands, results = [], []
    modes = ("runtime", "restart", "permission") if args.mode == "all" else (args.mode,)
    for mode in modes:
        repetitions = range(1, args.restart_repeats + 1) if mode == "restart" else (1,)
        for rep in repetitions:
            trial = output / f"{mode}-{rep}"
            game = trial / "game"
            prepare_game(game)
            # prepare/resume use distinct JVMs and one unchanged world directory;
            # every repetition starts in its own new game directory.
            phases = ("prepare", "resume") if mode == "restart" else ("measure",)
            prepare_ok = True
            for phase in phases:
                phase_output = trial / phase
                phase_output.mkdir()
                request_path = phase_output / "request.json"
                server_output = trial if mode == "restart" else phase_output
                request = {"mode": mode, "phase": phase, "repeat": rep, "rep": rep, "repeats": 10,
                           "restart_repeats": args.restart_repeats, "phase_ticks": 250, "max_calls": 3,
                           "deadline_seconds": 900, "output": str(server_output),
                           "trial_output": str(trial), "state_file": str(trial / "restart-state.json"),
                           "prepare_output": str(trial / "prepare"), "game_directory": str(game),
                           "providers_file": str(providers), "provider_id": args.provider_id}
                save(request_path, request)
                command = command_for(game, request_path)
                commands.append({"mode": mode, "repeat": rep, "phase": phase, "command": command})
                save(output / "commands.json", commands)
                if args.prepare_only:
                    continue
                if phase == "resume" and not prepare_ok:
                    evidence = {"started": False, "reason": "prepare_phase_failed", "infrastructure_ok": False}
                    save(phase_output / "launcher-status.json", evidence)
                else:
                    result_file = server_output / ("checkpoint.json" if mode == "restart" and phase == "prepare" else "result.json")
                    evidence = launch(command, phase_output, game, secrets, args.watchdog_seconds, result_file)
                results.append({"mode": mode, "repeat": rep, "phase": phase, **evidence})
                save(output / "results.json", results)
                if phase == "prepare":
                    prepare_ok = evidence.get("infrastructure_ok", False) and evidence.get("server_result", {}).get("success") is True
    print(f"{'Prepared' if args.prepare_only else 'Finished'} supplemental measurements: {output}")
    if not args.prepare_only and any(not r.get("infrastructure_ok") or r.get("server_result", {}).get("success") is not True for r in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
