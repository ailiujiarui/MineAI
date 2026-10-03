"""Offline checks for live-run authorization, evidence handling, and private config isolation."""

from contextlib import ExitStack, redirect_stdout
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("run_live_eval", Path(__file__).with_name("run_live_eval.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class LiveLauncherTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.runs = self.base / "build/evals/live-v1"
        self.output = self.runs / "sample"
        self.library = self.base / "private/providers.json"
        self.library.parent.mkdir()
        self.selected = {"id": "eval-model", "name": "Evaluation", "provider": "openai",
                         "model": "custom-model", "api_key": "test-private-key-123",
                         "base_url": "http://localhost:9999/v1", "ctx": 12345}
        self.other = {**self.selected, "id": "unrelated", "api_key": "unrelated-private-key-456"}
        runner.write_json(self.library, {"entries": [self.selected, self.other], "unrelated": "do not copy"})
        self.scenario = json.loads((runner.SCENARIOS / "craft-planks.json").read_text(encoding="utf-8"))

    def main(self, arguments):
        capture = io.StringIO()
        with ExitStack() as stack:
            stack.enter_context(patch.object(runner, "RUNS", self.runs))
            stack.enter_context(patch.object(runner, "git", return_value="a" * 40))
            stack.enter_context(patch.object(runner, "source_files_for", return_value=[Path(runner.__file__)]))
            launch = stack.enter_context(patch.object(runner, "launch"))
            stack.enter_context(redirect_stdout(capture))
            code = runner.main(["--output", str(self.output), *arguments])
        return code, launch, capture.getvalue()

    def provider_args(self):
        return ["--providers", str(self.library), "--provider-id", "eval-model"]

    def read_run(self):
        return json.loads((self.output / "run.json").read_text(encoding="utf-8"))

    def test_prepare_without_secrets_or_budget_produces_unscored_blockers(self):
        code, launch, stdout = self.main(["--prepare-only"])
        self.assertEqual(0, code)
        launch.assert_not_called()
        row = self.read_run()
        self.assertEqual("prepared", row["status"])
        self.assertIsNone(row["success"])
        self.assertEqual(4, len(row["blockers"]))
        self.assertFalse((self.output / "results/result.json").exists())
        self.assertIn("prepared", stdout)

    def test_missing_any_explicit_budget_prevents_model_launch(self):
        code, launch, _ = self.main([*self.provider_args(), "--max-model-calls", "4", "--max-seconds", "60"])
        self.assertEqual(2, code)
        launch.assert_not_called()
        self.assertEqual("infra_error", self.read_run()["status"])
        self.assertIn("--max-tokens", self.read_run()["reason"])
        self.assertFalse((self.output / "game/config/numen/providers.json").exists())

    def test_empty_key_blocks_launch_without_echoing_library(self):
        self.selected["api_key"] = ""
        runner.write_json(self.library, {"entries": [self.selected]})
        code, launch, stdout = self.main([*self.provider_args(), "--max-model-calls", "4",
                                       "--max-seconds", "60", "--max-tokens", "1000"])
        self.assertEqual(2, code)
        launch.assert_not_called()
        self.assertIn("nonempty api_key", stdout)

    def test_only_chosen_entry_is_copied_and_no_secret_enters_manifest(self):
        code, launch, stdout = self.main(["--prepare-only", *self.provider_args()])
        self.assertEqual(0, code)
        launch.assert_not_called()
        copied_path = self.output / "game/config/numen/providers.json"
        self.assertEqual({"entries": [self.selected]}, json.loads(copied_path.read_text(encoding="utf-8")))
        for path in self.output.rglob("*"):
            if path.is_file() and path != copied_path:
                contents = path.read_text(encoding="utf-8")
                self.assertNotIn(self.selected["api_key"], contents)
                self.assertNotIn(self.other["api_key"], contents)
        self.assertNotIn(self.selected["api_key"], stdout)
        request = json.loads((self.output / "request.json").read_text(encoding="utf-8"))
        self.assertEqual("eval-model", request["provider_id"])
        self.assertEqual(str(self.output / "results"), request["output_dir"])
        self.assertNotIn("api_key", json.dumps(request))

    def test_output_cannot_escape_private_build_tree(self):
        self.output = self.base / "shareable-results"
        code, launch, _ = self.main(["--prepare-only"])
        self.assertEqual(2, code)
        launch.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_existing_directory_is_not_reused(self):
        self.output.mkdir(parents=True)
        sentinel = self.output / "world-save"
        sentinel.write_text("preserve", encoding="utf-8")
        code, launch, _ = self.main(["--prepare-only"])
        self.assertEqual(2, code)
        launch.assert_not_called()
        self.assertEqual("preserve", sentinel.read_text(encoding="utf-8"))

    def test_nonpositive_budget_is_rejected_even_for_preparation(self):
        code, launch, _ = self.main(["--prepare-only", "--max-tokens", "0"])
        self.assertEqual(2, code)
        launch.assert_not_called()
        self.assertIn("max_tokens", self.read_run()["reason"])

    def test_malformed_provider_json_does_not_echo_source(self):
        self.library.write_text('{"api_key":"secret-value", BROKEN', encoding="utf-8")
        code, launch, stdout = self.main(["--prepare-only", *self.provider_args()])
        self.assertEqual(2, code)
        launch.assert_not_called()
        self.assertNotIn("secret-value", stdout)
        self.assertNotIn("secret-value", (self.output / "run.json").read_text(encoding="utf-8"))

    def test_duplicate_provider_id_fails_closed(self):
        runner.write_json(self.library, {"entries": [self.selected, self.selected]})
        with self.assertRaisesRegex(runner.PreflightError, "exactly one"):
            runner.select_provider(self.library, "eval-model")

    def test_unknown_provider_fields_are_not_copied(self):
        self.selected["unrelated_credential"] = "private-other-credential"
        runner.write_json(self.library, {"entries": [self.selected]})
        selected = runner.select_provider(self.library, "eval-model")
        self.assertNotIn("unrelated_credential", selected)

    def test_explicit_environment_override_is_in_launch_arguments(self):
        command = runner.command_for(self.output, offline=True, neoforge_version="21.1.233")
        self.assertIn(":core:neoforge:runClient", command)
        self.assertIn("--no-daemon", command)
        self.assertIn("--offline", command)
        self.assertIn("-Pneoforge_version=21.1.233", command)
        self.assertIn(f"-PnumenLiveEvalGameDir={self.output / 'game'}", command)
        self.assertNotIn(self.selected["api_key"], str(command))

    def test_process_log_redacts_secret_before_writing(self):
        self.output.mkdir(parents=True)
        secret = self.selected["api_key"]
        result = runner.launch([sys.executable, "-c", f"print({secret!r})"], self.output, 10, [secret])
        self.assertEqual(0, result["exit_code"])
        self.assertEqual("[REDACTED]\n", (self.output / "launcher.log").read_text(encoding="utf-8"))

    def test_timeout_is_recorded_as_timeout(self):
        self.output.mkdir(parents=True)
        result = runner.launch([sys.executable, "-c", "import time; time.sleep(30)"], self.output, .1, [])
        self.assertTrue(result["timeout"])
        self.assertEqual(124, result["exit_code"])
        self.assertLess(result["duration_ms"], 10000)

    def test_all_packaged_scenarios_validate_and_setup_does_not_supply_success(self):
        for path in runner.SCENARIOS.glob("*.json"):
            with self.subTest(path=path):
                scenario = runner.validate_scenario(json.loads(path.read_text(encoding="utf-8")))
                stock = scenario["setup"].get("inventory", {})
                requirements = scenario["success"].get("items", {})
                if requirements:
                    self.assertFalse(all(stock.get(item, 0) >= count for item, count in requirements.items()))
                self.assertNotIn("budget", scenario)

    def test_unknown_criterion_cannot_be_silently_ignored(self):
        scenario = copy.deepcopy(self.scenario)
        scenario["success"]["unknown_requirement"] = True
        with self.assertRaises(runner.PreflightError):
            runner.validate_scenario(scenario)

    def survival_scenario(self):
        scenario = copy.deepcopy(self.scenario)
        scenario.update(evaluation_version="survival-v1", case_id="survival.sustainable",
                        world_observer="sustainable-survival-v1", success_hold_ticks=1200,
                        success={"world": {"renewable_food": True, "food_nutrition": 24}},
                        milestones=[{"id": "shelter", "condition": {"world": {"sheltered": True}}}])
        return scenario

    def test_both_protocol_versions_preserve_the_scenario(self):
        for scenario in (self.scenario, self.survival_scenario()):
            with self.subTest(version=scenario["evaluation_version"]):
                self.assertEqual(scenario, runner.validate_scenario(copy.deepcopy(scenario)))

    def test_survival_preparation_records_its_actual_version(self):
        scenario_path = self.base / "survival.json"
        scenario = self.survival_scenario()
        runner.write_json(scenario_path, scenario)
        code, launch, _ = self.main(["--prepare-only", "--scenario", str(scenario_path)])
        self.assertEqual(0, code)
        launch.assert_not_called()
        self.assertEqual("survival-v1", self.read_run()["evaluation_version"])
        request = json.loads((self.output / "request.json").read_text(encoding="utf-8"))
        self.assertEqual("survival-v1", request["scenario"]["evaluation_version"])
        self.assertEqual(1200, request["scenario"]["success_hold_ticks"])

    def test_unknown_scenario_fields_versions_and_observers_are_rejected(self):
        changes = [{"unknown_field": True}]
        changes += [{"evaluation_version": value} for value in ("live-v2", "survival-v0", None, 1, [])]
        changes += [{"world_observer": value} for value in ("unknown", "", None, True, [], {})]
        for change in changes:
            with self.subTest(change=change):
                scenario = self.survival_scenario()
                scenario.update(change)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_scenario(scenario)

    def test_success_hold_is_an_explicit_positive_integer_game_tick_count(self):
        for ticks in (0, -1, 1.5, "1200", True, None, float("inf")):
            with self.subTest(ticks=ticks):
                scenario = self.survival_scenario()
                scenario["success_hold_ticks"] = ticks
                with self.assertRaises(runner.PreflightError):
                    runner.validate_scenario(scenario)
        scenario = self.survival_scenario()
        scenario["success_hold_ticks"] = 1
        runner.validate_scenario(scenario)

    def test_world_criteria_reject_empty_nested_or_nonthreshold_values(self):
        values = [{}, [], {"": True}, {" ": 1}]
        values += [{"food_nutrition": value} for value in
                   (False, -1, "24", None, {}, [], float("nan"), float("inf"), 10 ** 400)]
        for world in values:
            with self.subTest(world=world):
                scenario = self.survival_scenario()
                scenario["success"] = {"world": world}
                with self.assertRaises(runner.PreflightError):
                    runner.validate_scenario(scenario)
        scenario = self.survival_scenario()
        scenario["success"] = {"world": {"renewable_food": True, "food_nutrition": 0, "reserve": 1.5}}
        runner.validate_scenario(scenario)

    def test_empty_or_malformed_success_cannot_pass_vacuously(self):
        for success in ({}, {"items": {}}, {"items": {"minecraft:oak_planks": True}}, {"min_health": float("nan")}):
            with self.subTest(success=success):
                scenario = copy.deepcopy(self.scenario)
                scenario["success"] = success
                with self.assertRaises(runner.PreflightError):
                    runner.validate_scenario(scenario)

    def test_budget_in_scenario_cannot_authorize_spending(self):
        scenario = copy.deepcopy(self.scenario)
        scenario["budget"] = {"max_model_calls": 100000}
        with self.assertRaisesRegex(runner.PreflightError, "command line"):
            runner.validate_scenario(scenario)

    def test_duplicate_milestone_ids_are_rejected(self):
        scenario = copy.deepcopy(self.scenario)
        milestone = {"id": "same", "condition": {"min_health": 1}}
        scenario["milestones"] = [milestone, milestone]
        with self.assertRaisesRegex(runner.PreflightError, "unique IDs"):
            runner.validate_scenario(scenario)

    def write_result(self, **changes):
        results = self.output / "results"
        results.mkdir(parents=True, exist_ok=True)
        row = {"evaluation_version": self.scenario["evaluation_version"], "case_id": self.scenario["case_id"],
               "spec": self.scenario, "status": "success", "reason": "World inventory reached target",
               "elapsed_ms": 123.456, "model_calls": 1, "denied_model_calls": 0,
               "usage_reports": 1, "unknown_usage_reports": 0, "usage_complete": True,
               "tokens": {"input": 300, "output": 20, "cache_read": 0, "cache_write": 0, "total": 320},
               "milestones": [], "initial_snapshot": {"inventory": {"minecraft:oak_log": 2}},
               "final_snapshot": {"inventory": {"minecraft:oak_planks": 8}}}
        if "success_hold_ticks" in self.scenario:
            row["success_held_ticks"] = self.scenario["success_hold_ticks"]
        row.update(changes)
        runner.write_json(results / "result.json", row)
        runner.write_json(results / "trace.json", [{"type": "snapshot", "elapsed_ms": 123.456,
                                                   "data": row["final_snapshot"]}])
        return row

    def test_valid_world_result_preserves_fractional_millisecond_latency(self):
        expected = self.write_result()
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def survival_evidence(self):
        return {"initial_snapshot": {"world": {"renewable_food": False, "food_nutrition": 0, "sheltered": False}},
                "final_snapshot": {"world": {"renewable_food": True, "food_nutrition": 24, "sheltered": True}}}

    def test_survival_result_accepts_complete_observer_evidence(self):
        self.scenario = self.survival_scenario()
        expected = self.write_result(**self.survival_evidence())
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_result_version_must_match_requested_scenario(self):
        for version, other in (("live-v1", "survival-v1"), ("survival-v1", "live-v1")):
            with self.subTest(version=version):
                self.scenario["evaluation_version"] = version
                self.write_result(evaluation_version=other)
                with self.assertRaisesRegex(runner.PreflightError, "version"):
                    runner.validate_result(self.output, self.scenario)

    def test_failed_survival_result_needs_evidence_but_not_success_thresholds(self):
        self.scenario = self.survival_scenario()
        evidence = self.survival_evidence()
        evidence["final_snapshot"] = copy.deepcopy(evidence["initial_snapshot"])
        for status in ("failed", "budget_exhausted"):
            with self.subTest(status=status):
                expected = self.write_result(status=status, **evidence)
                self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_death_remains_scored_when_body_relative_world_evidence_is_unavailable(self):
        self.scenario = self.survival_scenario()
        for dead in ({"alive": False, "deaths": 1}, {"alive": True, "deaths": 1}):
            with self.subTest(dead=dead):
                evidence = self.survival_evidence()
                evidence["initial_snapshot"].update(alive=True, deaths=0)
                evidence["final_snapshot"] = dead
                expected = self.write_result(status="failed", reason="companion_died", **evidence)
                self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        expected = self.write_result(status="failed", reason="companion_died",
                                     initial_snapshot={"alive": False, "deaths": 0},
                                     final_snapshot={"alive": False, "deaths": 0})
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_missing_world_evidence_requires_real_death_evidence_and_failure_status(self):
        self.scenario = self.survival_scenario()
        for change in ({"final_snapshot": {"alive": True, "deaths": 0}},
                       {"final_snapshot": {"alive": True, "deaths": "1"}},
                       {"status": "success"}, {"reason": "another_failure"}):
            with self.subTest(change=change):
                evidence = self.survival_evidence()
                evidence["initial_snapshot"].update(alive=True, deaths=0)
                evidence.update(status="failed", reason="companion_died",
                                final_snapshot={"alive": False, "deaths": 1})
                evidence.update(change)
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_survival_success_requires_sufficient_held_game_ticks(self):
        self.scenario = self.survival_scenario()
        for ticks in (0, 1199, 1200.5, "1200", True, None, float("inf")):
            with self.subTest(ticks=ticks):
                self.write_result(success_held_ticks=ticks, **self.survival_evidence())
                with self.assertRaisesRegex(runner.PreflightError, "success_held_ticks"):
                    runner.validate_result(self.output, self.scenario)
        row = self.write_result(**self.survival_evidence())
        del row["success_held_ticks"]
        runner.write_json(self.output / "results/result.json", row)
        with self.assertRaisesRegex(runner.PreflightError, "success_held_ticks"):
            runner.validate_result(self.output, self.scenario)
        expected = self.write_result(success_held_ticks=1201, **self.survival_evidence())
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_failed_survival_result_does_not_require_completed_hold(self):
        self.scenario = self.survival_scenario()
        expected = self.write_result(status="budget_exhausted", success_held_ticks=0, **self.survival_evidence())
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_survival_result_rejects_missing_or_malformed_world_evidence(self):
        self.scenario = self.survival_scenario()
        for status in ("success", "failed", "budget_exhausted"):
            for key in ("initial_snapshot", "final_snapshot"):
                worlds = [None, {}, {"renewable_food": True, "food_nutrition": 24}]
                worlds += [{"renewable_food": value, "food_nutrition": 24, "sheltered": True}
                           for value in (1, "true", {}, None)]
                worlds += [{"renewable_food": True, "food_nutrition": value, "sheltered": True}
                           for value in (True, -1, "24", [], None, float("nan"), float("inf"))]
                for world in worlds:
                    with self.subTest(status=status, snapshot=key, world=world):
                        evidence = self.survival_evidence()
                        evidence[key] = {"world": world}
                        self.write_result(status=status, **evidence)
                        with self.assertRaises(runner.PreflightError):
                            runner.validate_result(self.output, self.scenario)

    def test_successful_survival_result_must_meet_final_world_thresholds(self):
        self.scenario = self.survival_scenario()
        for change in ({"renewable_food": False}, {"food_nutrition": 23}):
            with self.subTest(change=change):
                evidence = self.survival_evidence()
                evidence["final_snapshot"]["world"].update(change)
                self.write_result(**evidence)
                with self.assertRaisesRegex(runner.PreflightError, "criterion"):
                    runner.validate_result(self.output, self.scenario)

    def test_missing_result_does_not_become_capability_failure(self):
        with self.assertRaisesRegex(runner.PreflightError, "Client result"):
            runner.validate_result(self.output, self.scenario)

    def test_old_or_unfinished_result_is_rejected(self):
        for changes in ({"case_id": "previous-case"}, {"evaluation_version": "live-v0"},
                        {"status": "running"}, {"spec": {}}):
            with self.subTest(changes=changes):
                self.write_result(**changes)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_scored_result_requires_world_evidence_and_usage_telemetry(self):
        for changes in ({"final_snapshot": None}, {"initial_snapshot": {}}, {"tokens": None},
                        {"model_calls": -1}, {"usage_complete": None}):
            with self.subTest(changes=changes):
                self.write_result(**changes)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_infrastructure_error_can_record_failure_before_world_creation(self):
        expected = self.write_result(status="infra_error", reason="Could not create world",
                                     initial_snapshot=None, final_snapshot=None)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_missing_trace_invalidates_otherwise_successful_result(self):
        self.write_result()
        (self.output / "results/trace.json").unlink()
        with self.assertRaisesRegex(runner.PreflightError, "trace"):
            runner.validate_result(self.output, self.scenario)


if __name__ == "__main__":
    unittest.main()
