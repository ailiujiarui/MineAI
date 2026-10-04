"""Exercise the real client/world harness with scripted localhost SSE, never a model baseline."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import uuid

import run_live_eval
from run_bot_eval import write_json


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        has_result = any(message.get("role") == "tool" for message in payload["messages"])
        if self.server.calibration_case == "expedition-preparation":
            delta = {"content": "Scripted negative control: no expedition actions performed."}
            finish = "stop"
        elif has_result:
            delta = {"content": "Scripted harness fixture: crafting request completed."}
            finish = "stop"
        else:
            delta = {"tool_calls": [{"index": 0, "id": "fixture-craft", "type": "function",
                                     "function": {"name": "craft", "arguments": json.dumps({
                                         "item_id": "minecraft:oak_planks", "count": 8})}}]}
            finish = "tool_calls"
        chunks = [
            {"choices": [{"index": 0, "delta": delta, "finish_reason": None}]},
            {"choices": [{"index": 0, "delta": {}, "finish_reason": finish}]},
            # Explicitly artificial telemetry validates the recorder, not model token consumption.
            {"choices": [], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}},
        ]
        body = "".join("data: " + json.dumps(chunk) + "\n\n" for chunk in chunks) + "data: [DONE]\n\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body.encode())))
        self.end_headers()
        self.wfile.write(body.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--neoforge-version")
    parser.add_argument("--scenario", choices=("craft-planks", "expedition-preparation"), default="craft-planks",
                        help="Crafting success control or expedition starter-inventory negative control")
    args = parser.parse_args()
    identifier = uuid.uuid4().hex
    output = run_live_eval.RUNS / ("calibration-" + identifier)
    private = run_live_eval.ROOT / "build/evals/live-calibration" / identifier
    private.mkdir(parents=True)
    calibration = {"calibration_only": True, "real_model": False,
                   "usage_is_synthetic": True, "capability_baseline": False}
    scenario = json.loads((run_live_eval.SCENARIOS / (args.scenario + ".json")).read_text(encoding="utf-8"))
    scenario["source"] = "Scripted localhost transport calibration; no model inference or capability score"
    scenario_file = private / "scenario.json"
    write_json(scenario_file, scenario)
    with ThreadingHTTPServer(("127.0.0.1", 0), Fixture) as server:
        server.calibration_case = args.scenario
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        provider = private / "providers.json"
        write_json(provider, {"entries": [{"id": "fixture", "name": "Scripted harness fixture",
                                          "provider": "openai", "model": "scripted-loopback-fixture",
                                          "api_key": "local-fixture-placeholder",
                                          "base_url": f"http://127.0.0.1:{server.server_port}/v1",
                                          "proxy": "", "ctx": 64000}]})
        command = ["--providers", str(provider), "--provider-id", "fixture", "--scenario", str(scenario_file),
                   "--max-model-calls", "4", "--max-seconds", "45", "--max-tokens", "1000",
                   "--output", str(output)]
        if args.offline:
            command.append("--offline")
        if args.neoforge_version:
            command += ["--neoforge-version", args.neoforge_version]
        print("HARNESS CALIBRATION ONLY: scripted localhost replies and synthetic usage; no real model.", flush=True)
        try:
            code = run_live_eval.main(command)
        finally:
            server.shutdown()
    check = {"scenario": args.scenario, "launcher_exit_code": code}
    if args.scenario == "expedition-preparation":
        result_path = output / "results/result.json"
        result = json.loads(result_path.read_text(encoding="utf-8")) if result_path.exists() else {}
        initial = result.get("initial_snapshot") or {}
        final = result.get("final_snapshot") or {}
        check.update(
            purpose="Reject the real starter inventory as an expedition success with idle localhost replies",
            expected_outcome="budget_exhausted",
            starter_inventory_matches=initial.get("inventory") == scenario["setup"]["inventory"],
            no_milestones=result.get("milestones") == [],
            observer_rejected_incomplete_gear=all(
                snapshot.get("world", {}).get(field) is False
                for snapshot in (initial, final)
                for field in ("mining_tool_ready", "shield_ready", "armor_ready")),
            initial_food_nutrition=initial.get("world", {}).get("food_nutrition"))
        check["passed"] = (code == 0 and result.get("status") == "budget_exhausted"
                           and check["starter_inventory_matches"] and check["no_milestones"]
                           and check["observer_rejected_incomplete_gear"]
                           and check["initial_food_nutrition"] == 20)
        if not check["passed"]:
            code = 2
    else:
        check["purpose"] = "Verify actual client, tool transport, world crafting, observation and reporting"
    write_json(output / "calibration.json", calibration | check)
    for filename in ("run.json", "results/result.json", "results/progress.json"):
        path = output / filename
        if path.exists():
            record = json.loads(path.read_text(encoding="utf-8"))
            write_json(path, record | calibration)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
