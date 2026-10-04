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
        if self.server.calibration_case in {"expedition-preparation", "end-resources"}:
            stage = "expedition" if self.server.calibration_case == "expedition-preparation" else "end-resource"
            delta = {"content": f"Scripted negative control: no {stage} actions performed."}
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


def end_resources_negative_check(code, result, scenario):
    initial = result.get("initial_snapshot") or {}
    final = result.get("final_snapshot") or {}
    expected_inventory = dict(scenario["setup"].get("inventory", {}))
    for item in scenario["setup"].get("equipment", {}).values():
        expected_inventory[item] = expected_inventory.get(item, 0) + 1
    initial_world = initial.get("world", {})
    armor = initial_world.get("evidence", {}).get("armor", {})
    offhand = [item for item in initial_world.get("evidence", {}).get("inventory", [])
               if item.get("slot") == 40]
    resource_fields = ("eye_equivalents", "ender_eyes", "ender_pearls", "blaze_powder",
                       "blaze_rods", "pearl_units", "blaze_units")
    resource_items = ("minecraft:ender_eye", "minecraft:ender_pearl",
                      "minecraft:blaze_powder", "minecraft:blaze_rod")
    check = {
        "purpose": "Reject the prepared expedition inventory as end-resource success with idle localhost replies",
        "expected_outcome": "budget_exhausted",
        "scripted_request_budget_reached": result.get("reason") == "max_model_calls"
                                          and result.get("model_calls") == 4,
        "starter_inventory_matches": initial.get("inventory") == expected_inventory,
        "initial_resources_zero": all(initial_world.get(field) == 0 for field in resource_fields)
                                  and all(initial.get("inventory", {}).get(item, 0) == 0 for item in resource_items),
        "final_resources_zero": all(final.get("world", {}).get(field) == 0 for field in resource_fields)
                                and all(final.get("inventory", {}).get(item, 0) == 0 for item in resource_items),
        "initial_worn_armor_matches": all(
            armor.get(slot, {}).get("id") == item
            and armor.get(slot, {}).get("count") == 1
            and armor.get(slot, {}).get("remaining_durability") == armor.get(slot, {}).get("max_durability")
            and armor.get(slot, {}).get("max_durability", 0) > 0
            for slot, item in scenario["setup"].get("equipment", {}).items() if slot != "offhand"),
        "initial_offhand_shield_matches": len(offhand) == 1
                                          and offhand[0].get("id") == "minecraft:shield"
                                          and offhand[0].get("id") == scenario["setup"].get("equipment", {}).get("offhand")
                                          and offhand[0].get("count") == 1
                                          and offhand[0].get("max_durability", 0) > 0
                                          and offhand[0].get("remaining_durability") == offhand[0].get("max_durability"),
        "initial_equipment_ready": all(initial_world.get(field) is True
                                       for field in ("mining_tool_ready", "shield_ready", "armor_ready")),
        "initial_food_nutrition": initial_world.get("food_nutrition"),
        "no_milestones": result.get("milestones") == [],
        "absent_travel_uncovered": all(
            snapshot.get("world", {}).get("evidence", {}).get("coverage", {}).get(field, {}).get("status")
            == "not_covered"
            and snapshot.get("world", {}).get("evidence", {}).get("coverage", {}).get(field, {}).get("sampled_events")
            == 0
            for snapshot in (initial, final) for field in ("dimension_change", "sampled_chunk_change")),
        "event_coverage_consistent": all(
            isinstance(entry, dict) and type(entry.get("sampled_events")) is int
            and ((entry["sampled_events"] == 0 and entry.get("status") == "not_covered"
                  and entry.get("first_event") is None)
                 or (entry["sampled_events"] > 0 and entry.get("status") == "observed"
                     and isinstance(entry.get("first_event"), dict)))
            for snapshot in (initial, final)
            for field in ("dimension_change", "sampled_chunk_change", "inventory_change", "danger_entered", "danger_cleared")
            for entry in [snapshot.get("world", {}).get("evidence", {}).get("coverage", {}).get(field)]),
        "replanning_not_measured": all(
            snapshot.get("world", {}).get("evidence", {}).get("coverage", {}).get(field, {}).get("status")
            == "not_measured"
            for snapshot in (initial, final) for field in ("risk_replanning", "resource_site_replanning")),
    }
    check["passed"] = (code == 0 and result.get("status") == "budget_exhausted"
                       and all(check[field] for field in ("starter_inventory_matches", "initial_resources_zero",
                               "final_resources_zero", "initial_worn_armor_matches", "initial_equipment_ready",
                               "no_milestones", "absent_travel_uncovered", "event_coverage_consistent",
                               "replanning_not_measured", "scripted_request_budget_reached",
                               "initial_offhand_shield_matches"))
                       and check["initial_food_nutrition"] == 128)
    return check


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--neoforge-version")
    parser.add_argument("--scenario", choices=("craft-planks", "expedition-preparation", "end-resources"),
                        default="craft-planks",
                        help="Crafting success control or stage starter-inventory negative control")
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
    if args.scenario == "end-resources":
        result_path = output / "results/result.json"
        result = json.loads(result_path.read_text(encoding="utf-8")) if result_path.exists() else {}
        check.update(end_resources_negative_check(code, result, scenario))
        if not check["passed"]:
            code = 2
    elif args.scenario == "expedition-preparation":
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
