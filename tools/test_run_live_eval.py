"""Offline checks for live-run authorization, evidence handling, and private config isolation."""

from contextlib import ExitStack, redirect_stdout
import copy
import hashlib
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
calibration_spec = importlib.util.spec_from_file_location("calibrate_live_eval", Path(__file__).with_name("calibrate_live_eval.py"))
calibrator = importlib.util.module_from_spec(calibration_spec)
calibration_spec.loader.exec_module(calibrator)


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

    def main(self, arguments, launch_effect=None):
        capture = io.StringIO()
        with ExitStack() as stack:
            stack.enter_context(patch.object(runner, "RUNS", self.runs))
            stack.enter_context(patch.object(runner, "git", return_value="a" * 40))
            stack.enter_context(patch.object(runner, "source_files_for", return_value=[Path(runner.__file__)]))
            launch = stack.enter_context(patch.object(runner, "launch", side_effect=launch_effect))
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

    def test_chat_capture_preserves_full_evidence_and_redacts_credentials(self):
        companion = "00000000-0000-0000-0000-000000000001"
        source = self.output / "game/config/numen/companions" / companion / "chat.jsonl"
        source.parent.mkdir(parents=True)
        (self.output / "results").mkdir()
        escaped_secret = 'test-private-key-"\\value'
        rows = [{"type": "header", "v": 3},
                {"role": "assistant", "content": self.selected["api_key"],
                 "reasoning": "Wait for task_finished; end this turn.",
                 "extras": {"reasoning_content": "Full streamed reasoning", "secret": escaped_secret}},
                {"role": "user", "content": '<task_finished status="stopped"/><reflex>defend</reflex>'}]
        original = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows).encode("utf-8")
        source.write_bytes(original)
        private_config = source.parent / "binding.json"
        private_config.write_text(self.other["api_key"], encoding="utf-8")

        capture = runner.capture_companion_chat(self.output, [self.selected["api_key"], escaped_secret])

        target = self.output / capture["file_ref"]
        captured = target.read_bytes()
        evidence = [json.loads(line) for line in captured.decode("utf-8").splitlines()]
        self.assertEqual("captured", capture["status"])
        self.assertEqual(companion, capture["companion_uuid"])
        self.assertEqual(source.relative_to(self.output).as_posix(), capture["source_ref"])
        self.assertEqual(hashlib.sha256(captured).hexdigest(), capture["sha256"])
        self.assertEqual(rows[0], evidence[0])
        self.assertEqual(rows[1]["reasoning"], evidence[1]["reasoning"])
        self.assertEqual(rows[1]["extras"]["reasoning_content"], evidence[1]["extras"]["reasoning_content"])
        self.assertEqual("[REDACTED]", evidence[1]["content"])
        self.assertEqual("[REDACTED]", evidence[1]["extras"]["secret"])
        self.assertEqual(rows[2], evidence[2])
        self.assertNotIn(self.selected["api_key"], captured.decode("utf-8"))
        self.assertNotIn(json.dumps(escaped_secret)[1:-1], captured.decode("utf-8"))
        self.assertEqual(original, source.read_bytes())
        self.assertEqual([target], list((self.output / "results").iterdir()))

    def test_abnormal_launch_retains_partial_chat_and_reports_unavailable_or_ambiguous_chat(self):
        for name, exit_code, timed_out, interrupted, chat_count in (
                ("timeout", 124, True, False, 1), ("interrupted", 130, False, True, 1),
                ("early-failure", 1, False, False, 0), ("ambiguous", 0, False, False, 2)):
            with self.subTest(name=name):
                self.output = self.runs / name

                def fake_launch(command, output, timeout, secrets):
                    for index in range(chat_count):
                        chat = output / "game/config/numen/companions" / str(index) / "chat.jsonl"
                        chat.parent.mkdir(parents=True)
                        chat.write_text(json.dumps({"role": "assistant", "reasoning": "partial evidence",
                                                    "content": self.selected["api_key"]}) + "\n", encoding="utf-8")
                    return {"argv": command, "exit_code": exit_code,
                            "timeout": timed_out, "interrupted": interrupted}

                code, launch, stdout = self.main([*self.provider_args(), "--max-model-calls", "4",
                                                 "--max-seconds", "60", "--max-tokens", "1000"], fake_launch)
                launch.assert_called_once()
                row = self.read_run()
                self.assertEqual(2, code)
                self.assertEqual("infra_error", row["status"])
                self.assertIsNone(row["success"])
                self.assertEqual(exit_code, row["command"]["exit_code"])
                self.assertNotIn(self.selected["api_key"], stdout)
                if chat_count == 1:
                    capture = row["companion_chat"]
                    captured = (self.output / capture["file_ref"]).read_bytes()
                    self.assertEqual("captured", capture["status"])
                    self.assertEqual(hashlib.sha256(captured).hexdigest(), capture["sha256"])
                    self.assertIn("partial evidence", captured.decode("utf-8"))
                    self.assertNotIn(self.selected["api_key"], captured.decode("utf-8"))
                    self.assertIn("did not complete normally", row["reason"])
                elif chat_count == 0:
                    self.assertEqual("unavailable", row["companion_chat"]["status"])
                    self.assertFalse((self.output / "results/chat.jsonl").exists())
                else:
                    self.assertIn("exactly one", row["reason"])
                    self.assertFalse((self.output / "results/chat.jsonl").exists())

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
                if scenario["evaluation_version"] == "end-resources-v1":
                    self.assertEqual(0, stock.get("minecraft:ender_eye", 0) + min(
                        stock.get("minecraft:ender_pearl", 0), stock.get("minecraft:blaze_powder", 0)
                        + 2 * stock.get("minecraft:blaze_rod", 0)))
                    self.assertGreater(scenario["success"]["world"]["eye_equivalents"], 0)
                elif requirements:
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

    def free_survival_scenario(self):
        scenario = copy.deepcopy(self.scenario)
        scenario.update(evaluation_version="survival-v2", case_id="survival.free",
                        world_observer="free-survival-v2", success_hold_ticks=1200,
                        success={"world": {"food_nutrition": 20, "shelter": True}},
                        milestones=[{"id": "shelter", "condition": {"world": {"shelter": True}}}])
        return scenario

    def expedition_scenario(self):
        scenario = copy.deepcopy(self.scenario)
        scenario.update(evaluation_version="expedition-v1", case_id="expedition.prepare",
                        world_observer="expedition-v1", success_hold_ticks=1200,
                        setup={"inventory": {"minecraft:stone_pickaxe": 1, "minecraft:bread": 4}},
                        success={"world": {"equipment_ready": True, "food_nutrition": 48}},
                        milestones=[{"id": "equipment", "condition": {"world": {"equipment_ready": True}}}])
        return scenario

    def end_resources_scenario(self):
        scenario = copy.deepcopy(self.scenario)
        scenario.update(evaluation_version="end-resources-v1", case_id="live.end-resources",
                        world_observer="end-resources-v1", success_hold_ticks=200,
                        setup={"inventory": {"minecraft:iron_pickaxe": 1, "minecraft:cooked_beef": 16},
                               "equipment": {"head": "minecraft:iron_helmet",
                                             "chest": "minecraft:iron_chestplate",
                                             "legs": "minecraft:iron_leggings", "feet": "minecraft:iron_boots",
                                             "offhand": "minecraft:shield"}},
                        success={"world": {"eye_equivalents": 16, "armor_ready": True}},
                        milestones=[{"id": "eye-resources", "condition": {"world": {"eye_equivalents": 16}}}])
        return scenario

    def end_entry_scenario(self):
        scenario = self.end_resources_scenario()
        scenario.update(evaluation_version="end-entry-v1", case_id="live.end-entry",
                        world_observer="end-entry-v1", success_hold_ticks=20,
                        success={"min_health": 16, "min_food": 16, "world": {"native_end_entry": True}},
                        milestones=[{"id": "portal-reached", "condition": {"world": {"portal_reached": True}}},
                                    {"id": "portal-activated", "condition": {"world": {"portal_activated_by_companion": True}}},
                                    {"id": "entered-end", "condition": {"world": {"native_end_entry": True}}}])
        scenario["setup"]["inventory"].update({"minecraft:ender_eye": 8,
                                               "minecraft:ender_pearl": 8,
                                               "minecraft:blaze_rod": 4})
        return scenario

    def dragon_combat_scenario(self):
        scenario = self.end_resources_scenario()
        scenario.update(evaluation_version="dragon-combat-v1", case_id="live.dragon-combat", seed=73005,
                        world_preset="normal", world_observer="dragon-combat-v1", success_hold_ticks=200,
                        success={"world": {"native_dragon_kill": True, "dragon_post_kill_alive_ticks": 200}},
                        milestones=[{"id": "dragon-damaged", "condition": {"world": {"dragon_damaged": True}}},
                                    {"id": "dragon-death-started", "condition": {"world": {"dragon_death_started": True}}},
                                    {"id": "native-dragon-kill", "condition": {"world": {"native_dragon_kill": True}}}])
        scenario["setup"].update(spawn=[100.5, 49.0, 0.5], dimension="minecraft:the_end", native_dragon_battle=True,
                                  inventory={"minecraft:iron_sword": 1, "minecraft:iron_pickaxe": 1,
                                             "minecraft:iron_axe": 1, "minecraft:water_bucket": 1,
                                             "minecraft:cooked_beef": 32, "minecraft:cobblestone": 128,
                                             "minecraft:bow": 1, "minecraft:arrow": 128})
        return scenario

    def test_supported_protocol_versions_preserve_the_scenario(self):
        legacy_observer = {**self.scenario, "world_observer": "sustainable-survival-v1"}
        for scenario in (self.scenario, legacy_observer, self.survival_scenario(),
                         self.free_survival_scenario(), self.expedition_scenario(), self.end_resources_scenario(),
                         self.end_entry_scenario(), self.dragon_combat_scenario()):
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

    def test_free_survival_preparation_preserves_its_version_and_observer(self):
        scenario_path = self.base / "free-survival.json"
        scenario = self.free_survival_scenario()
        scenario["setup"].update(inventory={}, food_level=14, saturation=0)
        runner.write_json(scenario_path, scenario)
        code, launch, _ = self.main(["--prepare-only", "--scenario", str(scenario_path)])
        self.assertEqual(0, code)
        launch.assert_not_called()
        self.assertEqual("survival-v2", self.read_run()["evaluation_version"])
        request = json.loads((self.output / "request.json").read_text(encoding="utf-8"))
        self.assertEqual({**scenario, "budget": self.read_run()["budget"]}, request["scenario"])

    def test_expedition_preparation_preserves_fixed_supplies_without_food_initialization(self):
        scenario_path = self.base / "expedition.json"
        scenario = self.expedition_scenario()
        runner.write_json(scenario_path, scenario)
        code, launch, _ = self.main(["--prepare-only", "--scenario", str(scenario_path)])
        self.assertEqual(0, code)
        launch.assert_not_called()
        self.assertEqual("expedition-v1", self.read_run()["evaluation_version"])
        request = json.loads((self.output / "request.json").read_text(encoding="utf-8"))
        self.assertEqual({**scenario, "budget": self.read_run()["budget"]}, request["scenario"])

    def test_end_resources_preparation_preserves_public_worn_equipment_without_reading_provider(self):
        scenario_path = self.base / "end-resources.json"
        scenario = self.end_resources_scenario()
        runner.write_json(scenario_path, scenario)
        with patch.object(runner, "select_provider", side_effect=AssertionError("Do not read credentials")):
            code, launch, _ = self.main(["--prepare-only", "--scenario", str(scenario_path)])
        self.assertEqual(0, code)
        launch.assert_not_called()
        self.assertEqual("end-resources-v1", self.read_run()["evaluation_version"])
        self.assertIsNone(self.read_run()["success"])
        request = json.loads((self.output / "request.json").read_text(encoding="utf-8"))
        self.assertEqual({**scenario, "budget": self.read_run()["budget"]}, request["scenario"])
        self.assertNotIn("minecraft:shield", request["scenario"]["setup"]["inventory"])
        self.assertFalse((self.output / "game/config/numen/providers.json").exists())

    def test_end_resources_equipment_accepts_slot_registry_ids_and_preserves_omissions(self):
        for equipment in (None, {}, {"head": "minecraft:iron_helmet"}, {"offhand": "minecraft:shield"}):
            with self.subTest(equipment=equipment):
                scenario = self.end_resources_scenario()
                if equipment is None:
                    del scenario["setup"]["equipment"]
                else:
                    scenario["setup"]["equipment"] = equipment
                self.assertEqual(scenario, runner.validate_scenario(copy.deepcopy(scenario)))

    def test_end_entry_prepare_preserves_mixed_resources_and_does_not_read_credentials(self):
        scenario = self.end_entry_scenario()
        path = self.base / "end-entry.json"
        runner.write_json(path, scenario)
        with patch.object(runner, "select_provider", side_effect=AssertionError("Do not read credentials")):
            code, launch, _ = self.main(["--prepare-only", "--scenario", str(path)])
        self.assertEqual(0, code)
        launch.assert_not_called()
        self.assertEqual("end-entry-v1", self.read_run()["evaluation_version"])
        self.assertIsNone(self.read_run()["success"])
        request = json.loads((self.output / "request.json").read_text(encoding="utf-8"))
        self.assertEqual({**scenario, "budget": self.read_run()["budget"]}, request["scenario"])
        stock = request["scenario"]["setup"]["inventory"]
        self.assertEqual(16, stock["minecraft:ender_eye"] + min(stock["minecraft:ender_pearl"],
                                                             2 * stock["minecraft:blaze_rod"]))
        self.assertFalse((self.output / "game/config/numen/providers.json").exists())

    def test_end_entry_observer_pairing_and_equipment_follow_the_new_protocol(self):
        scenario = self.end_entry_scenario()
        self.assertEqual(scenario, runner.validate_scenario(copy.deepcopy(scenario)))
        for observer in ("end-resources-v1", "expedition-v1", "sustainable-survival-v1", "free-survival-v2"):
            with self.subTest(observer=observer):
                invalid = copy.deepcopy(scenario)
                invalid["world_observer"] = observer
                with self.assertRaisesRegex(runner.PreflightError, "world_observer"):
                    runner.validate_scenario(invalid)
        for equipment in ({"mainhand": "minecraft:iron_sword"}, {"head": 1}, {"head": "iron_helmet"}):
            with self.subTest(equipment=equipment):
                invalid = copy.deepcopy(scenario)
                invalid["setup"]["equipment"] = equipment
                with self.assertRaisesRegex(runner.PreflightError, "equipment"):
                    runner.validate_scenario(invalid)

    def test_dragon_combat_prepare_preserves_public_end_start_without_reading_credentials(self):
        scenario = self.dragon_combat_scenario()
        path = self.base / "dragon-combat.json"
        runner.write_json(path, scenario)
        with patch.object(runner, "select_provider", side_effect=AssertionError("Do not read credentials")):
            code, launch, _ = self.main(["--prepare-only", "--scenario", str(path)])
        self.assertEqual(0, code)
        launch.assert_not_called()
        self.assertEqual("dragon-combat-v1", self.read_run()["evaluation_version"])
        self.assertIsNone(self.read_run()["success"])
        request = json.loads((self.output / "request.json").read_text(encoding="utf-8"))
        self.assertEqual({**scenario, "budget": self.read_run()["budget"]}, request["scenario"])
        self.assertFalse((self.output / "game/config/numen/providers.json").exists())

    def test_dragon_combat_initialization_is_explicit_and_does_not_modify_the_normal_arena(self):
        for change in ({"dimension": "minecraft:overworld"}, {"native_dragon_battle": False},
                       {"native_dragon_battle": 1}, {"spawn": [0.5, 49.0, 0.5]},
                       {"floor": {"from": [0, 48, 0], "to": [10, 48, 10], "block": "minecraft:stone"}},
                       {"blocks": []}, {"fixed_time": 0}, {"food_level": 20}, {"saturation": 5}):
            with self.subTest(change=change):
                invalid = self.dragon_combat_scenario()
                invalid["setup"].update(change)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_scenario(invalid)
        for change in ({"world_preset": "flat"}, {"world_observer": "end-entry-v1"}):
            with self.subTest(change=change):
                invalid = self.dragon_combat_scenario()
                invalid.update(change)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_scenario(invalid)
        for field in ("dimension", "native_dragon_battle", "spawn"):
            with self.subTest(missing=field):
                invalid = self.dragon_combat_scenario()
                invalid["setup"].pop(field)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_scenario(invalid)

    def test_old_versions_cannot_gain_native_end_battle_initialization(self):
        for original in (self.scenario, self.free_survival_scenario(), self.end_entry_scenario()):
            with self.subTest(version=original["evaluation_version"]):
                invalid = copy.deepcopy(original)
                invalid["setup"].update(dimension="minecraft:the_end", native_dragon_battle=True)
                with self.assertRaisesRegex(runner.PreflightError, "setup fields"):
                    runner.validate_scenario(invalid)

    def test_dragon_combat_protocol_fixes_the_confirmed_post_kill_hold_at_200_ticks(self):
        for ticks in (None, 0, 1, 20, 199, 201, True, "200"):
            with self.subTest(ticks=ticks):
                scenario = self.dragon_combat_scenario()
                if ticks is None:
                    scenario.pop("success_hold_ticks")
                else:
                    scenario["success_hold_ticks"] = ticks
                with self.assertRaises(runner.PreflightError):
                    runner.validate_scenario(scenario)
    def test_end_resources_equipment_rejects_unknown_slots_counts_and_malformed_ids(self):
        for equipment in (None, [], "iron", {"mainhand": "minecraft:iron_pickaxe"},
                          {"HEAD": "minecraft:iron_helmet"}, {"head": "iron_helmet"},
                          {"head": "minecraft:"}, {"head": 1}, {"head": True},
                          {"head": {"item": "minecraft:iron_helmet", "count": 2}},
                          {"head": ["minecraft:iron_helmet"]}):
            with self.subTest(equipment=equipment):
                scenario = self.end_resources_scenario()
                scenario["setup"]["equipment"] = equipment
                with self.assertRaisesRegex(runner.PreflightError, "equipment"):
                    runner.validate_scenario(scenario)

    def test_existing_versions_cannot_gain_equipment_initialization(self):
        for original in (self.scenario, self.survival_scenario(), self.free_survival_scenario(),
                         self.expedition_scenario()):
            with self.subTest(version=original["evaluation_version"]):
                scenario = copy.deepcopy(original)
                scenario["setup"]["equipment"] = {"offhand": "minecraft:shield"}
                with self.assertRaisesRegex(runner.PreflightError, "setup fields"):
                    runner.validate_scenario(scenario)

    def test_free_survival_food_setup_accepts_bounds_and_preserves_omissions(self):
        for food_setup in ({}, {"food_level": 14, "saturation": 0},
                           {"food_level": 0, "saturation": 0},
                           {"food_level": 20, "saturation": 20},
                           {"food_level": 14, "saturation": 14.0},
                           {"food_level": 4}, {"saturation": 5.5}):
            with self.subTest(setup=food_setup):
                scenario = self.free_survival_scenario()
                scenario["setup"].update(food_setup)
                self.assertEqual(scenario, runner.validate_scenario(copy.deepcopy(scenario)))

    def test_free_survival_food_setup_rejects_invalid_types_and_ranges(self):
        food_values = (-1, 21, 14.0, True, False, None, "14", [], {}, float("nan"), float("inf"))
        saturation_values = (-1, 15, True, False, None, "0", [], {}, float("nan"), float("inf"))
        changes = [{"food_level": value} for value in food_values]
        changes += [{"food_level": 14, "saturation": value} for value in saturation_values]
        changes += [{"saturation": 20.5}, {"food_level": 0, "saturation": 0.1}]
        for change in changes:
            with self.subTest(setup=change):
                scenario = self.free_survival_scenario()
                scenario["setup"].update(change)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_scenario(scenario)

    def test_versions_other_than_survival_v2_reject_food_initialization_fields(self):
        for original in (self.scenario, self.survival_scenario(), self.expedition_scenario(),
                         self.end_resources_scenario()):
            for food_setup in ({"food_level": 14}, {"saturation": 0},
                               {"food_level": 14, "saturation": 0}):
                with self.subTest(version=original["evaluation_version"], setup=food_setup):
                    scenario = copy.deepcopy(original)
                    scenario["setup"].update(food_setup)
                    with self.assertRaisesRegex(runner.PreflightError, "setup fields"):
                        runner.validate_scenario(scenario)

    def test_unknown_scenario_fields_versions_and_observers_are_rejected(self):
        changes = [{"unknown_field": True}]
        changes += [{"evaluation_version": value} for value in
                    ("live-v2", "survival-v0", "survival-v3", "expedition-v2", "end-resources-v2", None, 1, [])]
        changes += [{"world_observer": value} for value in ("unknown", "", None, True, [], {})]
        for original in (self.survival_scenario(), self.free_survival_scenario(), self.expedition_scenario(),
                         self.end_resources_scenario()):
            for change in changes:
                with self.subTest(version=original["evaluation_version"], change=change):
                    scenario = copy.deepcopy(original)
                    scenario.update(change)
                    with self.assertRaises(runner.PreflightError):
                        runner.validate_scenario(scenario)

    def test_observers_cannot_reinterpret_another_protocol_version(self):
        for version, observer in (("live-v1", "free-survival-v2"),
                                  ("survival-v1", "free-survival-v2"),
                                  ("survival-v2", "sustainable-survival-v1"),
                                  ("live-v1", "expedition-v1"), ("survival-v1", "expedition-v1"),
                                  ("survival-v2", "expedition-v1"),
                                  ("expedition-v1", "sustainable-survival-v1"),
                                  ("expedition-v1", "free-survival-v2"),
                                  ("expedition-v1", "end-resources-v1"),
                                  ("end-resources-v1", "expedition-v1"),
                                  ("end-resources-v1", "sustainable-survival-v1"),
                                  ("end-resources-v1", "free-survival-v2")):
            with self.subTest(version=version, observer=observer):
                scenario = self.free_survival_scenario()
                scenario.update(evaluation_version=version, world_observer=observer)
                with self.assertRaisesRegex(runner.PreflightError, "world_observer"):
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

    def dragon_combat_snapshots(self, completed=True, actor=None):
        scenario = self.dragon_combat_scenario()
        subject = "876bfafe-92e3-4804-88f4-7b162d245292"
        target = "61f2d118-78bf-422d-8728-1a0b246a5a22"
        identity = {"subject_uuid": subject, "target_uuid": target, "origin": [0, 0, 0],
                    "fight_id": "minecraft:the_end:0:0:0:" + target, "dimension": "minecraft:the_end"}
        actor = actor or subject
        first = identity | {"game_tick": 10, "origin": [0, 0, 0], "calibration_fixture": False,
                            "evidence_source": "server_native_dragon_fight_initial_state", "fight_bound": True,
                            "needs_state_scanning": False, "dragon_killed": False, "previously_killed": False,
                            "is_respawning": False, "target_present": True, "target_health": 200,
                            "target_max_health": 200, "target_no_ai": False, "target_invulnerable": False,
                            "crystals_alive": 10, "dragon_kills": 0, "death_animation_ticks": 0, "phase": "HoldingPattern",
                            "crystals": [{"uuid": f"00000000-0000-0000-0000-{index:012x}",
                                          "position": [index * 10, 80, index], "invulnerable": False, "alive": True}
                                         for index in range(1, 11)]}
        current = copy.deepcopy(first)
        current.update(death_animation_ticks=0, phase="HoldingPattern")
        chain = identity | {"calibration_fixture": False, "initial_battle": first, "current_battle": current,
                            "first_death_observed": False, "first_death": None, "observation_gaps": 0,
                            "post_kill_alive_ticks": 0, "post_kill_hold_start_game_tick": None,
                            "post_kill_last_verified_game_tick": None,
                            "critical_evidence_complete": True, "attribution_ambiguous": False,
                            "last_verified_game_tick": 10, "total_actual_damage": 0,
                            "companion_actual_damage": 0, "minimum_health": 200, "current_health": 200,
                            "lethal": None, "kill_credit": None, "kill_statistic": None, "fight_end": None,
                            "first_damage": None, "first_companion_damage": None, "fatal_damage": None}
        inventory = dict(scenario["setup"]["inventory"])
        inventory.update({item: 1 for item in scenario["setup"]["equipment"].values()})
        armor = {slot: {"id": item, "count": 1, "max_durability": 100, "remaining_durability": 100}
                 for slot, item in scenario["setup"]["equipment"].items() if slot != "offhand"}
        stacks = [{"slot": slot, "id": item, "count": 1, "max_durability": 100, "remaining_durability": 100}
                  for slot, item in enumerate(inventory) if item not in {"minecraft:shield"}]
        stacks.append({"slot": 40, "id": "minecraft:shield", "count": 1,
                       "max_durability": 336, "remaining_durability": 336})
        world = {"subject_uuid": subject, "native_dragon_kill": False, "dragon_combat_ready": True,
                 "observation_complete": True, "dragon_damaged": False, "dragon_death_started": False,
                 "dragon_post_kill_alive_ticks": 0,
                 "evidence": {"dragon_combat": chain, "armor": armor, "inventory": stacks}}
        initial = {"companion_uuid": subject, "subject_present": True, "alive": True, "game_tick": 10,
                   "dragon_kills": 0, "deaths": 0, "dimension": "minecraft:the_end",
                   "position": [100.5, 49.0, 0.5], "health": 20, "food": 20, "inventory": inventory, "world": world}
        final = copy.deepcopy(initial)
        final["game_tick"] = 430 if completed else 11
        end_chain = final["world"]["evidence"]["dragon_combat"]
        end_chain["last_verified_game_tick"] = final["game_tick"]
        end_chain["current_battle"]["game_tick"] = final["game_tick"]
        if completed:
            damage = identity | {"game_tick": 30, "event_id": 1,
                                 "evidence_source": "native_ender_dragon_really_hurt", "health_before": 200,
                                 "health_after": 0, "actual_health_loss": 200, "actor_uuid": actor,
                                 "attribution_origin": "native_damage_source", "provenance_actor_uuid": actor,
                                 "attribution_reliable": True, "source_actor_uuid": actor,
                                 "direct_entity_uuid": actor, "damage_type_message_id": "player"}
            end_chain.update(total_actual_damage=200, companion_actual_damage=200 if actor == subject else 0,
                             minimum_health=0, current_health=None, fatal_damage=damage,
                             first_damage=copy.deepcopy(damage), first_companion_damage=damage if actor == subject else None)
            end_chain["lethal"] = identity | {"game_tick": 30, "evidence_source": "native_living_entity_die",
                                             "damage_type_message_id": "player", "source_actor_uuid": actor,
                                             "direct_entity_uuid": actor, "credited_actor_uuid": actor, "health": 0,
                                             "attribution_origin": "native_damage_source", "provenance_actor_uuid": actor,
                                             "health_before": 200, "health_after": 0, "actual_health_loss": 200,
                                             "damage_event_id": 1, "fatal_damage": copy.deepcopy(damage)}
            end_chain["kill_credit"] = identity | {"game_tick": 30,
                "evidence_source": "native_server_player_award_kill_score", "actor_uuid": actor,
                "damage_type_message_id": "player", "source_actor_uuid": actor, "direct_entity_uuid": actor}
            end_chain["kill_statistic"] = identity | {"game_tick": 30,
                "evidence_source": "native_player_killed_entity_statistic", "actor_uuid": actor, "before": 0, "after": 1}
            end_chain["fight_end"] = identity | {"game_tick": 230,
                "evidence_source": "native_end_dragon_fight_set_dragon_killed", "dragon_killed": True,
                "previously_killed": True, "death_animation_ticks": 200}
            final["dragon_kills"] = 1 if actor == subject else 0
            end_chain["current_battle"].update(target_present=False, target_health=None, phase="dying",
                                                death_animation_ticks=200, dragon_killed=True, previously_killed=True,
                                                dragon_kills=final["dragon_kills"])
            final["world"].update(native_dragon_kill=actor == subject, dragon_damaged=actor == subject,
                                    dragon_death_started=True, dragon_post_kill_alive_ticks=200 if actor == subject else 0)
            end_chain.update(post_kill_alive_ticks=200 if actor == subject else 0,
                             post_kill_hold_start_game_tick=231 if actor == subject else None,
                             post_kill_last_verified_game_tick=430 if actor == subject else None)
        return {"initial_snapshot": initial, "final_snapshot": final}

    def test_dragon_combat_native_death_credit_statistic_and_animation_form_one_kill(self):
        self.scenario = self.dragon_combat_scenario()
        expected = self.write_result(**self.dragon_combat_snapshots())
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_dragon_combat_idle_start_is_not_success_but_is_a_valid_budget_result(self):
        self.scenario = self.dragon_combat_scenario()
        snapshots = self.dragon_combat_snapshots(completed=False)
        self.write_result(**snapshots)
        with self.assertRaises(runner.PreflightError):
            runner.validate_result(self.output, self.scenario)
        expected = self.write_result(status="budget_exhausted", reason="max_model_calls", model_calls=4, **snapshots)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        self.assertTrue(calibrator.dragon_combat_negative_check(0, expected, self.scenario)["passed"])

    def test_dragon_combat_does_not_count_other_targets_players_or_historical_statistics(self):
        self.scenario = self.dragon_combat_scenario()
        for defect in ("other_target", "other_fight", "other_subject", "other_actor", "history", "initial_dead",
                       "initial_no_ai", "initial_invulnerable", "initial_crystals", "duplicate_crystals", "dead_crystal"):
            with self.subTest(defect=defect):
                snapshots = self.dragon_combat_snapshots()
                chain = snapshots["final_snapshot"]["world"]["evidence"]["dragon_combat"]
                if defect in {"other_target", "other_fight", "other_subject"}:
                    field = {"other_target": "target_uuid", "other_fight": "fight_id", "other_subject": "subject_uuid"}[defect]
                    chain["lethal"][field] = "e46c062b-5e06-481f-be71-711289251b99"
                elif defect == "other_actor":
                    chain["kill_credit"]["actor_uuid"] = "e46c062b-5e06-481f-be71-711289251b99"
                elif defect == "history":
                    snapshots["initial_snapshot"]["dragon_kills"] = 1
                else:
                    initial = snapshots["initial_snapshot"]["world"]["evidence"]["dragon_combat"]["initial_battle"]
                    if defect == "initial_dead":
                        initial["dragon_killed"] = True
                    elif defect == "initial_no_ai":
                        initial["target_no_ai"] = True
                    elif defect == "initial_invulnerable":
                        initial["target_invulnerable"] = True
                    elif defect == "initial_crystals":
                        initial["crystals_alive"] = 9
                    elif defect == "dead_crystal":
                        initial["crystals"][0]["alive"] = False
                    else:
                        initial["crystals"][1] = initial["crystals"][0]
                self.write_result(**snapshots)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_dragon_combat_known_other_player_kill_is_a_valid_failure(self):
        self.scenario = self.dragon_combat_scenario()
        evidence = self.dragon_combat_snapshots(actor="e46c062b-5e06-481f-be71-711289251b99")
        expected = self.write_result(status="failed", reason="target_killed_by_other", **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_dragon_combat_missing_entity_or_unfinished_animation_cannot_be_inferred_as_a_kill(self):
        self.scenario = self.dragon_combat_scenario()
        for defect in ("disappearance", "before_animation", "state_only", "stale", "gap", "critical", "order"):
            with self.subTest(defect=defect):
                snapshots = self.dragon_combat_snapshots()
                chain = snapshots["final_snapshot"]["world"]["evidence"]["dragon_combat"]
                if defect == "disappearance":
                    chain["fight_end"] = None
                elif defect == "before_animation":
                    chain["fight_end"]["death_animation_ticks"] = 199
                elif defect == "state_only":
                    chain["lethal"] = None
                elif defect == "stale":
                    chain["last_verified_game_tick"] -= 1
                elif defect == "gap":
                    chain["observation_gaps"] = 1
                elif defect == "critical":
                    chain["critical_evidence_complete"] = False
                else:
                    chain["fight_end"]["game_tick"] = 29
                self.write_result(**snapshots)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_dragon_combat_rejects_state_edits_ambiguous_damage_replayed_statistics_and_completion_tick(self):
        self.scenario = self.dragon_combat_scenario()
        for defect in ("direct_death", "fatal_mismatch", "fatal_id", "zero_damage", "source_actor", "nearest_crystal",
                       "ambiguous", "duplicate_statistic", "completion_tick", "self_consistent_other_target", "fixture"):
            with self.subTest(defect=defect):
                evidence = self.dragon_combat_snapshots()
                final = evidence["final_snapshot"]
                chain = final["world"]["evidence"]["dragon_combat"]
                if defect == "direct_death":
                    chain["lethal"].pop("fatal_damage")
                elif defect == "fatal_mismatch":
                    chain["lethal"]["fatal_damage"]["source_actor_uuid"] = None
                elif defect == "fatal_id":
                    chain["lethal"]["damage_event_id"] = 2
                elif defect == "zero_damage":
                    chain["lethal"]["fatal_damage"]["health_before"] = 0
                elif defect == "source_actor":
                    chain["lethal"]["source_actor_uuid"] = "e46c062b-5e06-481f-be71-711289251b99"
                elif defect == "nearest_crystal":
                    chain["lethal"]["attribution_origin"] = "crystal_nearest_player_fallback"
                elif defect == "ambiguous":
                    chain["attribution_ambiguous"] = True
                elif defect == "duplicate_statistic":
                    chain["kill_statistic"]["after"] = 2
                    final["dragon_kills"] = chain["current_battle"]["dragon_kills"] = 2
                elif defect == "completion_tick":
                    final["game_tick"] = chain["last_verified_game_tick"] = chain["current_battle"]["game_tick"] = 230
                elif defect == "self_consistent_other_target":
                    other_target = "e46c062b-5e06-481f-be71-711289251b99"
                    for event in (chain, chain["initial_battle"], chain["current_battle"], chain["lethal"],
                                  chain["kill_credit"], chain["kill_statistic"], chain["fight_end"],
                                  chain["first_damage"], chain["first_companion_damage"], chain["lethal"]["fatal_damage"]):
                        event["target_uuid"] = other_target
                        event["fight_id"] = "minecraft:the_end:0:0:0:" + other_target
                else:
                    chain["calibration_fixture"] = chain["initial_battle"]["calibration_fixture"] = True
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_dragon_combat_fixture_raw_validation_is_explicit_and_never_a_formal_success(self):
        evidence = self.dragon_combat_snapshots()
        final = evidence["final_snapshot"]
        final["dimension"] = "minecraft:overworld"
        chain = final["world"]["evidence"]["dragon_combat"]
        for event in (chain, chain["initial_battle"], chain["current_battle"], chain["lethal"], chain["kill_credit"],
                      chain["kill_statistic"], chain["fight_end"], chain["first_companion_damage"],
                      chain["first_damage"], chain["lethal"]["fatal_damage"]):
            event["dimension"] = "minecraft:overworld"
            event["fight_id"] = "minecraft:overworld:0:0:0:" + chain["target_uuid"]
        chain["calibration_fixture"] = True
        chain["initial_battle"].update(calibration_fixture=True, target_health=1, target_no_ai=True,
                                      crystals=[], crystals_alive=0)
        chain["current_battle"].update(calibration_fixture=True, target_no_ai=True)
        final["world"].update(dragon_combat_ready=False, native_dragon_kill=False)
        final["world"]["dragon_post_kill_alive_ticks"] = 0
        chain.update(post_kill_alive_ticks=0, post_kill_hold_start_game_tick=None, post_kill_last_verified_game_tick=None)
        final["game_tick"] = chain["last_verified_game_tick"] = chain["current_battle"]["game_tick"] = 230
        with self.assertRaises(runner.PreflightError):
            runner.dragon_combat_evidence(final, "final_snapshot")
        self.assertEqual(chain, runner.dragon_combat_evidence(final, "fixture_snapshot", allow_calibration_fixture=True))
        final["game_tick"] = chain["last_verified_game_tick"] = chain["current_battle"]["game_tick"] = 430
        final["world"]["dragon_post_kill_alive_ticks"] = 200
        chain.update(post_kill_alive_ticks=200, post_kill_hold_start_game_tick=231, post_kill_last_verified_game_tick=430)
        self.assertEqual(chain, runner.dragon_combat_evidence(final, "fixture_snapshot", allow_calibration_fixture=True))
        self.scenario = self.dragon_combat_scenario()
        self.write_result(**evidence)
        with self.assertRaises(runner.PreflightError):
            runner.validate_result(self.output, self.scenario)

    def test_dragon_combat_diagnostic_truncation_does_not_destroy_retained_native_kill_receipts(self):
        self.scenario = self.dragon_combat_scenario()
        evidence = self.dragon_combat_snapshots()
        evidence["final_snapshot"]["world"]["evidence"].update(events=[], events_total=1000, events_truncated=True)
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_dragon_combat_completion_tick_is_pending_until_the_next_verified_server_tick(self):
        self.scenario = self.dragon_combat_scenario()
        evidence = self.dragon_combat_snapshots()
        final = evidence["final_snapshot"]
        chain = final["world"]["evidence"]["dragon_combat"]
        final["game_tick"] = chain["last_verified_game_tick"] = chain["current_battle"]["game_tick"] = 230
        final["world"]["native_dragon_kill"] = False
        final["world"]["dragon_post_kill_alive_ticks"] = 0
        chain.update(post_kill_alive_ticks=0, post_kill_hold_start_game_tick=None, post_kill_last_verified_game_tick=None)
        expected = self.write_result(status="budget_exhausted", **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        self.write_result(**evidence)
        with self.assertRaises(runner.PreflightError):
            runner.validate_result(self.output, self.scenario)
        final["game_tick"] = chain["last_verified_game_tick"] = chain["current_battle"]["game_tick"] = 231
        final["world"]["native_dragon_kill"] = True
        final["world"]["dragon_post_kill_alive_ticks"] = 1
        chain.update(post_kill_alive_ticks=1, post_kill_hold_start_game_tick=231, post_kill_last_verified_game_tick=231)
        expected = self.write_result(status="budget_exhausted", success_held_ticks=1, **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        final["game_tick"] = chain["last_verified_game_tick"] = chain["current_battle"]["game_tick"] = 430
        final["world"]["dragon_post_kill_alive_ticks"] = 200
        chain.update(post_kill_alive_ticks=200, post_kill_last_verified_game_tick=430)
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_dragon_combat_199_post_kill_ticks_are_pending_and_200_verified_ticks_succeed(self):
        self.scenario = self.dragon_combat_scenario()
        evidence = self.dragon_combat_snapshots()
        final = evidence["final_snapshot"]
        chain = final["world"]["evidence"]["dragon_combat"]
        final["game_tick"] = chain["last_verified_game_tick"] = chain["current_battle"]["game_tick"] = 429
        final["world"]["dragon_post_kill_alive_ticks"] = chain["post_kill_alive_ticks"] = 199
        chain["post_kill_last_verified_game_tick"] = 429
        expected = self.write_result(status="budget_exhausted", success_held_ticks=199, **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        self.write_result(**evidence)
        with self.assertRaises(runner.PreflightError):
            runner.validate_result(self.output, self.scenario)
        final["game_tick"] = chain["last_verified_game_tick"] = chain["current_battle"]["game_tick"] = 430
        final["world"]["dragon_post_kill_alive_ticks"] = chain["post_kill_alive_ticks"] = 200
        chain["post_kill_last_verified_game_tick"] = 430
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_dragon_combat_hold_requires_consistent_server_ticks_and_reading_cannot_extend_it(self):
        self.scenario = self.dragon_combat_scenario()
        evidence = self.dragon_combat_snapshots()
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        self.assertEqual(200, expected["final_snapshot"]["world"]["dragon_post_kill_alive_ticks"])
        for defect in ("world_only", "repeated_read_increment", "early_start", "stale_hold_tick", "missing_hold"):
            with self.subTest(defect=defect):
                invalid = self.dragon_combat_snapshots()
                final = invalid["final_snapshot"]
                chain = final["world"]["evidence"]["dragon_combat"]
                if defect == "world_only":
                    final["world"]["dragon_post_kill_alive_ticks"] = 201
                elif defect == "repeated_read_increment":
                    final["world"]["dragon_post_kill_alive_ticks"] = chain["post_kill_alive_ticks"] = 201
                elif defect == "early_start":
                    chain["post_kill_hold_start_game_tick"] = 230
                elif defect == "stale_hold_tick":
                    chain["post_kill_last_verified_game_tick"] = 429
                else:
                    chain.pop("post_kill_alive_ticks")
                self.write_result(**invalid)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_dragon_combat_can_leave_the_end_after_native_completion_without_a_return_requirement(self):
        self.scenario = self.dragon_combat_scenario()
        evidence = self.dragon_combat_snapshots()
        evidence["final_snapshot"]["dimension"] = "minecraft:overworld"
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        evidence = self.dragon_combat_snapshots(completed=False)
        evidence["final_snapshot"]["dimension"] = "minecraft:overworld"
        self.write_result(status="budget_exhausted", **evidence)
        with self.assertRaises(runner.PreflightError):
            runner.validate_result(self.output, self.scenario)

    def test_dragon_combat_death_during_post_kill_hold_fails_even_after_health_recovery(self):
        self.scenario = self.dragon_combat_scenario()
        evidence = self.dragon_combat_snapshots()
        final = evidence["final_snapshot"]
        chain = final["world"]["evidence"]["dragon_combat"]
        chain["first_death_observed"] = True
        chain["first_death"] = {"subject_uuid": final["companion_uuid"], "game_tick": 329,
                                "evidence_source": "native_companion_death_before_recovery"}
        chain.update(post_kill_alive_ticks=0, post_kill_hold_start_game_tick=None, post_kill_last_verified_game_tick=None)
        final["world"].update(native_dragon_kill=False, dragon_post_kill_alive_ticks=0)
        self.write_result(**evidence)
        with self.assertRaises(runner.PreflightError):
            runner.validate_result(self.output, self.scenario)
        expected = self.write_result(status="failed", reason="companion_died", success_held_ticks=0, **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_dragon_combat_retained_native_death_wins_when_the_latch_boolean_is_misreported(self):
        self.scenario = self.dragon_combat_scenario()
        evidence = self.dragon_combat_snapshots()
        final = evidence["final_snapshot"]
        chain = final["world"]["evidence"]["dragon_combat"]
        chain["first_death"] = {"subject_uuid": final["companion_uuid"], "game_tick": 329,
                                "evidence_source": "native_companion_death_before_recovery"}
        self.assertFalse(chain["first_death_observed"])
        self.write_result(**evidence)
        with self.assertRaises(runner.PreflightError):
            runner.validate_result(self.output, self.scenario)
        expected = self.write_result(status="failed", reason="companion_died", success_held_ticks=0, **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_dragon_combat_current_initial_state_cannot_hide_damage_after_the_saved_baseline(self):
        self.scenario = self.dragon_combat_scenario()
        for defect in ("damaged", "dying", "dying_phase", "already_killed", "previously_killed", "world_damage"):
            with self.subTest(defect=defect):
                evidence = self.dragon_combat_snapshots()
                initial = evidence["initial_snapshot"]
                chain = initial["world"]["evidence"]["dragon_combat"]
                if defect == "damaged":
                    chain["current_battle"]["target_health"] = chain["current_health"] = 199
                elif defect == "dying":
                    chain["current_battle"]["death_animation_ticks"] = 1
                elif defect == "dying_phase":
                    chain["current_battle"]["phase"] = "Dying (#9)"
                elif defect in {"already_killed", "previously_killed"}:
                    chain["current_battle"]["dragon_killed" if defect == "already_killed" else "previously_killed"] = True
                else:
                    chain["total_actual_damage"] = 1
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_dragon_combat_complete_damage_ledger_rejects_repeated_or_inflated_events(self):
        self.scenario = self.dragon_combat_scenario()
        for defect in (None, "duplicate", "inflated_total", "inflated_companion"):
            with self.subTest(defect=defect):
                evidence = self.dragon_combat_snapshots()
                raw = evidence["final_snapshot"]["world"]["evidence"]
                chain = raw["dragon_combat"]
                event = copy.deepcopy(chain["lethal"]["fatal_damage"])
                event["kind"] = "dragon_damage"
                raw.update(events=[event], events_total=1, events_truncated=False)
                if defect == "duplicate":
                    raw["events"].append(copy.deepcopy(event))
                    raw["events_total"] = 2
                elif defect == "inflated_total":
                    chain["total_actual_damage"] = 400
                elif defect == "inflated_companion":
                    chain["total_actual_damage"] = chain["companion_actual_damage"] = 400
                expected = self.write_result(**evidence)
                if defect is None:
                    self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
                else:
                    with self.assertRaises(runner.PreflightError):
                        runner.validate_result(self.output, self.scenario)

    def test_dragon_combat_first_death_wins_same_tick_mutual_kill_even_after_recovery(self):
        self.scenario = self.dragon_combat_scenario()
        for alive in (False, True):
            with self.subTest(alive=alive):
                evidence = self.dragon_combat_snapshots()
                final = evidence["final_snapshot"]
                final.update(alive=alive, deaths=1, health=20 if alive else 0)
                final["world"]["evidence"]["dragon_combat"]["first_death_observed"] = True
                final["world"]["evidence"]["dragon_combat"]["first_death"] = {
                    "subject_uuid": final["companion_uuid"], "game_tick": 230,
                    "evidence_source": "native_companion_death_before_recovery"}
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)
                expected = self.write_result(status="failed", reason="companion_died", **evidence)
                self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_dragon_combat_idle_calibration_rejects_missing_loadout_and_fake_combat_progress(self):
        self.scenario = self.dragon_combat_scenario()
        for defect in ("offhand", "bow_durability", "inventory", "subject", "progress", "milestones"):
            with self.subTest(defect=defect):
                evidence = self.dragon_combat_snapshots(completed=False)
                result = {"status": "budget_exhausted", "reason": "max_model_calls", "model_calls": 4,
                          "milestones": [], **evidence}
                initial, final = result["initial_snapshot"], result["final_snapshot"]
                if defect == "offhand":
                    initial["world"]["evidence"]["inventory"][-1]["slot"] = 0
                elif defect == "bow_durability":
                    next(stack for stack in initial["world"]["evidence"]["inventory"]
                         if stack["id"] == "minecraft:bow")["remaining_durability"] = 99
                elif defect == "inventory":
                    initial["inventory"]["minecraft:arrow"] = 127
                elif defect == "subject":
                    final["companion_uuid"] = "e46c062b-5e06-481f-be71-711289251b99"
                elif defect == "progress":
                    final["world"]["dragon_damaged"] = True
                else:
                    result["milestones"] = [{"id": "dragon-damaged", "elapsed_ms": 1}]
                self.assertFalse(calibrator.dragon_combat_negative_check(0, result, self.scenario)["passed"])

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

    def test_free_survival_result_scores_generic_boolean_and_numeric_world_facts(self):
        self.scenario = self.free_survival_scenario()
        evidence = {"initial_snapshot": {"world": {"food_nutrition": 0, "shelter": False}},
                    "final_snapshot": {"world": {"food_nutrition": 20, "shelter": True}}}
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        for change in ({"food_nutrition": 19}, {"shelter": False}):
            with self.subTest(change=change):
                incomplete = copy.deepcopy(evidence)
                incomplete["final_snapshot"]["world"].update(change)
                self.write_result(**incomplete)
                with self.assertRaisesRegex(runner.PreflightError, "criterion"):
                    runner.validate_result(self.output, self.scenario)

    def test_expedition_success_needs_complete_current_world_facts_and_completed_hold(self):
        self.scenario = self.expedition_scenario()
        evidence = {"initial_snapshot": {"world": {"equipment_ready": False, "food_nutrition": 20}},
                    "final_snapshot": {"world": {"equipment_ready": True, "food_nutrition": 48}}}
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        for change in ({"final_snapshot": {"world": {"equipment_ready": False, "food_nutrition": 48}}},
                       {"final_snapshot": {"world": {"equipment_ready": True, "food_nutrition": 47}}},
                       {"final_snapshot": {"world": {"equipment_ready": True}}},
                       {"success_held_ticks": 1199}):
            with self.subTest(change=change):
                self.write_result(**{**evidence, **change})
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_end_resources_initial_supplies_do_not_make_missing_resources_successful(self):
        self.scenario = self.end_resources_scenario()
        initial = {"world": {"eye_equivalents": 0, "armor_ready": True, "loadout_held_ticks": 0}}
        evidence = {"initial_snapshot": initial, "final_snapshot": copy.deepcopy(initial)}
        self.write_result(**evidence)
        with self.assertRaisesRegex(runner.PreflightError, "criterion"):
            runner.validate_result(self.output, self.scenario)
        expected = self.write_result(status="budget_exhausted", success_held_ticks=0, **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_end_resources_success_needs_current_resources_even_after_historical_milestone(self):
        self.scenario = self.end_resources_scenario()
        evidence = {"initial_snapshot": {"world": {"eye_equivalents": 0, "armor_ready": True,
                                                    "loadout_held_ticks": 0}},
                    "final_snapshot": {"world": {"eye_equivalents": 16, "armor_ready": True,
                                                  "loadout_held_ticks": 200}}}
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        for change in ({"final_snapshot": {"world": {"eye_equivalents": 15, "armor_ready": True,
                                                     "loadout_held_ticks": 200}}},
                       {"final_snapshot": {"world": {"eye_equivalents": 16, "armor_ready": False,
                                                     "loadout_held_ticks": 200}}},
                       {"success_held_ticks": 199}):
            with self.subTest(change=change):
                self.write_result(**{**evidence, "milestones": [{"id": "eye-resources", "elapsed_ms": 1}], **change})
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_end_resources_alive_snapshots_require_nonnegative_integer_observer_hold(self):
        self.scenario = self.end_resources_scenario()
        for status in ("success", "failed", "budget_exhausted"):
            for snapshot in ("initial_snapshot", "final_snapshot"):
                for held in (None, -1, True, 1.5, "200", float("inf"), "missing"):
                    with self.subTest(status=status, snapshot=snapshot, held=held):
                        evidence = {"initial_snapshot": {"alive": True, "world": {
                                        "eye_equivalents": 0, "armor_ready": True, "loadout_held_ticks": 0}},
                                    "final_snapshot": {"alive": True, "world": {
                                        "eye_equivalents": 16, "armor_ready": True, "loadout_held_ticks": 200}}}
                        if held == "missing":
                            del evidence[snapshot]["world"]["loadout_held_ticks"]
                        else:
                            evidence[snapshot]["world"]["loadout_held_ticks"] = held
                        self.write_result(status=status, **evidence)
                        with self.assertRaisesRegex(runner.PreflightError, "loadout_held_ticks"):
                            runner.validate_result(self.output, self.scenario)

    def test_end_resources_recovered_loadout_requires_a_fresh_uninterrupted_hold(self):
        self.scenario = self.end_resources_scenario()
        evidence = {"initial_snapshot": {"world": {"eye_equivalents": 0, "armor_ready": True,
                                                    "loadout_held_ticks": 0}},
                    "final_snapshot": {"world": {"eye_equivalents": 16, "armor_ready": True,
                                                  "loadout_held_ticks": 1}}}
        self.write_result(success_held_ticks=200, milestones=[{"id": "eye-resources", "elapsed_ms": 1}], **evidence)
        with self.assertRaisesRegex(runner.PreflightError, "loadout_held_ticks"):
            runner.validate_result(self.output, self.scenario)
        expected = self.write_result(status="budget_exhausted", success_held_ticks=200, **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))
        evidence["final_snapshot"]["world"]["loadout_held_ticks"] = 200
        expected = self.write_result(success_held_ticks=200, **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def end_resources_negative_evidence(self):
        scenario = self.end_resources_scenario()
        inventory = dict(scenario["setup"]["inventory"])
        inventory.update({item: 1 for item in scenario["setup"]["equipment"].values()})
        coverage = {field: {"status": "not_covered", "sampled_events": 0, "first_event": None}
                    for field in ("dimension_change", "sampled_chunk_change", "inventory_change",
                                  "danger_entered", "danger_cleared")}
        coverage.update({field: {"status": "not_measured"}
                         for field in ("risk_replanning", "resource_site_replanning")})
        armor = {slot: {"id": item, "count": 1, "max_durability": 100, "remaining_durability": 100}
                 for slot, item in scenario["setup"]["equipment"].items() if slot != "offhand"}
        world = {field: 0 for field in ("eye_equivalents", "ender_eyes", "ender_pearls", "blaze_powder",
                                       "blaze_rods", "pearl_units", "blaze_units")}
        world.update(mining_tool_ready=True, shield_ready=True, armor_ready=True, food_nutrition=128,
                     loadout_held_ticks=0, evidence={"armor": armor, "coverage": coverage,
                         "inventory": [{"slot": 40, "id": "minecraft:shield", "count": 1,
                                        "remaining_durability": 336, "max_durability": 336}]})
        snapshot = {"inventory": inventory, "world": world}
        return scenario, {"status": "budget_exhausted", "reason": "max_model_calls", "model_calls": 4,
                          "milestones": [], "initial_snapshot": snapshot,
                          "final_snapshot": copy.deepcopy(snapshot)}

    def test_end_resources_idle_calibration_checks_actual_wearing_and_no_target_resources(self):
        scenario, result = self.end_resources_negative_evidence()
        self.assertTrue(calibrator.end_resources_negative_check(0, result, scenario)["passed"])
        for initial, final in (({"eye_equivalents": 1}, {}), ({}, {"ender_pearl": 1})):
            with self.subTest(initial=initial, final=final):
                invalid = copy.deepcopy(result)
                invalid["initial_snapshot"]["world"].update(initial)
                if final:
                    invalid["final_snapshot"]["inventory"]["minecraft:ender_pearl"] = 1
                self.assertFalse(calibrator.end_resources_negative_check(0, invalid, scenario)["passed"])
        invalid = copy.deepcopy(result)
        invalid["initial_snapshot"]["world"]["evidence"]["armor"]["head"]["id"] = "minecraft:air"
        self.assertFalse(calibrator.end_resources_negative_check(0, invalid, scenario)["passed"])
        invalid = copy.deepcopy(result)
        invalid["initial_snapshot"]["inventory"]["minecraft:shield"] = 2
        self.assertFalse(calibrator.end_resources_negative_check(0, invalid, scenario)["passed"])
        invalid = copy.deepcopy(result)
        invalid["status"] = "success"
        self.assertFalse(calibrator.end_resources_negative_check(0, invalid, scenario)["passed"])

    def test_end_resources_idle_calibration_requires_one_full_durability_offhand_shield(self):
        scenario, result = self.end_resources_negative_evidence()
        for change in ({"slot": 0}, {"count": 2}, {"remaining_durability": 335},
                       {"id": "minecraft:iron_pickaxe"}, {"max_durability": 0}):
            with self.subTest(change=change):
                invalid = copy.deepcopy(result)
                invalid["initial_snapshot"]["world"]["evidence"]["inventory"][0].update(change)
                self.assertFalse(calibrator.end_resources_negative_check(0, invalid, scenario)["passed"])
        duplicate = copy.deepcopy(result)
        duplicate["initial_snapshot"]["world"]["evidence"]["inventory"] *= 2
        self.assertFalse(calibrator.end_resources_negative_check(0, duplicate, scenario)["passed"])

    def test_end_resources_idle_calibration_does_not_pass_uncovered_events_or_claim_replanning(self):
        scenario, result = self.end_resources_negative_evidence()
        for field, change in (("dimension_change", {"status": "observed"}),
                              ("danger_entered", {"status": "observed"}),
                              ("risk_replanning", {"status": "observed"})):
            with self.subTest(field=field):
                invalid = copy.deepcopy(result)
                invalid["final_snapshot"]["world"]["evidence"]["coverage"][field].update(change)
                self.assertFalse(calibrator.end_resources_negative_check(0, invalid, scenario)["passed"])
        # A sampled inventory change can be real even with idle replies because the body may eat.
        changed = copy.deepcopy(result)
        changed["final_snapshot"]["world"]["evidence"]["coverage"]["inventory_change"].update(
            status="observed", sampled_events=1, first_event={"kind": "inventory_change", "game_tick": 20})
        self.assertTrue(calibrator.end_resources_negative_check(0, changed, scenario)["passed"])

    def end_entry_portal_evidence(self, x=100, active=False):
        y, z = 32, 100
        frames = [{"x": x + offset, "y": y, "z": z - 2, "id": "minecraft:end_portal_frame",
                   "has_eye": active, "facing": "south"} for offset in (-1, 0, 1)]
        frames += [{"x": x + offset, "y": y, "z": z + 2, "id": "minecraft:end_portal_frame",
                    "has_eye": active, "facing": "north"} for offset in (-1, 0, 1)]
        frames += [{"x": x - 2, "y": y, "z": z + offset, "id": "minecraft:end_portal_frame",
                    "has_eye": active, "facing": "east"} for offset in (-1, 0, 1)]
        frames += [{"x": x + 2, "y": y, "z": z + offset, "id": "minecraft:end_portal_frame",
                    "has_eye": active, "facing": "west"} for offset in (-1, 0, 1)]
        if not active:
            for frame in frames[:2]:
                frame["has_eye"] = True
        return {"portal_id": f"minecraft:overworld:{x}:{y}:{z}", "dimension": "minecraft:overworld",
                "x": x, "y": y, "z": z, "generated_stronghold": True,
                "portal_origin": "generated_stronghold", "frames": frames,
                "portal_blocks": [{"x": x + dx, "y": y, "z": z + dz,
                                   "id": "minecraft:end_portal" if active else "minecraft:air"}
                                  for dx in (-1, 0, 1) for dz in (-1, 0, 1)],
                "eyes": 12 if active else 2, "active": active, "initial_eyes": 2,
                "initial_active": False, "companion_eye_insertions": 10 if active else 0,
                "other_eye_insertions": 0}

    def end_entry_snapshots(self):
        subject = "26ef93c4-6ce9-4570-8d9e-4aed8b3a220a"
        initial = {"companion_uuid": subject, "subject_present": True, "alive": True, "deaths": 0,
                   "game_tick": 0, "dimension": "minecraft:overworld", "health": 20, "food": 20,
                   "inventory": {"minecraft:ender_eye": 8, "minecraft:ender_pearl": 8, "minecraft:blaze_rod": 4},
                   "world": {"subject_uuid": subject, "portal_reached": False,
                             "portal_activated_by_companion": False, "native_end_entry": False,
                             "observation_complete": True, "end_alive_held_ticks": 0,
                             "evidence": {"end_entry": {"subject_uuid": subject, "portal": None,
                                           "arrival": None, "activation": None, "entry": None,
                                           "last_verified_game_tick": 0, "observation_gaps": 0,
                                           "native_entry_episode_active": False,
                                           "first_death_observed": False,
                                           "critical_evidence_complete": True}}}}
        final = copy.deepcopy(initial)
        final.update(game_tick=60, dimension="minecraft:the_end", inventory={})
        final["world"].update(portal_reached=True, portal_activated_by_companion=True,
                              native_end_entry=True, end_alive_held_ticks=31)
        chain = final["world"]["evidence"]["end_entry"]
        portal = self.end_entry_portal_evidence(active=True)
        chain.update(portal=portal, last_verified_game_tick=60, native_entry_episode_active=True)
        for field, tick, kind in (("arrival", 10, "portal_reached"), ("activation", 20, "portal_activated"),
                                  ("entry", 30, "native_end_entry")):
            chain[field] = {"kind": kind, "subject_uuid": subject, "game_tick": tick,
                            "dimension": "minecraft:overworld", "x": 100, "y": 32, "z": 100,
                            "portal_id": portal["portal_id"],
                            "portal": copy.deepcopy(portal if field != "arrival" else self.end_entry_portal_evidence())}
        chain["activation"].update(actor_uuid=subject, native_eye_inserted=True, eye_inventory_delta=-1,
                                    frame={"x": 99, "y": 32, "z": 98})
        chain["entry"].update(dimension="minecraft:the_end", from_dimension="minecraft:overworld",
                             destination_uuid=subject,
                             to_dimension="minecraft:the_end",
                             source_block={"x": 100, "y": 32, "z": 100},
                             evidence_source="vanilla_handle_portal_change_dimension")
        return {"initial_snapshot": initial, "final_snapshot": final}

    def test_end_entry_native_chain_accepts_normal_eye_consumption_and_server_hold(self):
        self.scenario = self.end_entry_scenario()
        evidence = self.end_entry_snapshots()
        self.assertEqual({}, evidence["final_snapshot"]["inventory"])
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_end_entry_items_known_coordinates_or_seen_inactive_frames_cannot_succeed(self):
        self.scenario = self.end_entry_scenario()
        for progress in ("none", "coordinates", "frames"):
            with self.subTest(progress=progress):
                evidence = self.end_entry_snapshots()
                final = evidence["final_snapshot"] = copy.deepcopy(evidence["initial_snapshot"])
                final.update(game_tick=60, known_stronghold_position=[100, 32, 100])
                final["world"]["evidence"]["end_entry"]["last_verified_game_tick"] = 60
                if progress == "frames":
                    final["world"]["evidence"]["end_entry"]["portal"] = self.end_entry_portal_evidence()
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)
                expected = self.write_result(status="budget_exhausted", success_held_ticks=0, **evidence)
                self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_end_entry_portal_geometry_active_blocks_and_identity_cannot_be_forged(self):
        self.scenario = self.end_entry_scenario()
        for defect in ("inactive", "frame", "facing", "different_portal", "duplicate_blocks", "fixture"):
            with self.subTest(defect=defect):
                evidence = self.end_entry_snapshots()
                chain = evidence["final_snapshot"]["world"]["evidence"]["end_entry"]
                if defect == "inactive":
                    chain["entry"]["portal"]["portal_blocks"][0]["id"] = "minecraft:air"
                elif defect == "frame":
                    chain["activation"]["portal"]["frames"][0]["id"] = "minecraft:stone"
                elif defect == "facing":
                    chain["activation"]["portal"]["frames"][0]["facing"] = "north"
                elif defect == "different_portal":
                    chain["entry"]["portal"] = self.end_entry_portal_evidence(x=200, active=True)
                    chain["entry"]["portal_id"] = chain["entry"]["portal"]["portal_id"]
                elif defect == "duplicate_blocks":
                    chain["activation"]["portal"]["portal_blocks"][1] = chain["activation"]["portal"]["portal_blocks"][0]
                else:
                    chain["arrival"]["portal"].update(generated_stronghold=False, portal_origin="calibration_fixture")
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_end_entry_direct_dimension_change_and_missing_native_eye_receipt_are_rejected(self):
        self.scenario = self.end_entry_scenario()
        for field, change in (("entry", {"evidence_source": "native_dimension_change_hook"}),
                              ("entry", {"evidence_source": "test_change_dimension"}),
                              ("entry", {"from_dimension": "minecraft:the_nether"}),
                              ("activation", {"native_eye_inserted": False}),
                              ("activation", {"eye_inventory_delta": 0}),
                              ("activation", {"frame": {"x": 0, "y": 0, "z": 0}})):
            with self.subTest(field=field, change=change):
                evidence = self.end_entry_snapshots()
                evidence["final_snapshot"]["world"]["evidence"]["end_entry"][field].update(change)
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_end_entry_native_actor_destination_and_first_death_need_explicit_attribution(self):
        self.scenario = self.end_entry_scenario()
        other = "484ef3c7-c6a6-441d-9958-bc4642337723"
        for event, field in (("activation", "actor_uuid"), ("entry", "destination_uuid")):
            for value in (None, other):
                with self.subTest(event=event, field=field, value=value):
                    evidence = self.end_entry_snapshots()
                    record = evidence["final_snapshot"]["world"]["evidence"]["end_entry"][event]
                    if value is None:
                        record.pop(field)
                    else:
                        record[field] = value
                    self.write_result(**evidence)
                    with self.assertRaises(runner.PreflightError):
                        runner.validate_result(self.output, self.scenario)
        for value in (None, True):
            with self.subTest(first_death_observed=value):
                evidence = self.end_entry_snapshots()
                chain = evidence["final_snapshot"]["world"]["evidence"]["end_entry"]
                if value is None:
                    chain.pop("first_death_observed")
                else:
                    chain["first_death_observed"] = value
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_end_entry_preopened_and_other_actor_activation_cannot_count_as_bot_work(self):
        self.scenario = self.end_entry_scenario()
        for change in ({"initial_active": True}, {"initial_eyes": 12}, {"other_eye_insertions": 1},
                       {"companion_eye_insertions": 9}):
            with self.subTest(change=change):
                evidence = self.end_entry_snapshots()
                evidence["final_snapshot"]["world"]["evidence"]["end_entry"]["activation"]["portal"].update(change)
                self.write_result(**evidence)
                with self.assertRaisesRegex(runner.PreflightError, "attribute"):
                    runner.validate_result(self.output, self.scenario)

    def test_end_entry_cross_dimension_snapshots_and_events_require_the_same_subject(self):
        self.scenario = self.end_entry_scenario()
        other = "484ef3c7-c6a6-441d-9958-bc4642337723"
        for target in ("snapshot", "world", "chain", "entry"):
            with self.subTest(target=target):
                evidence = self.end_entry_snapshots()
                final = evidence["final_snapshot"]
                chain = final["world"]["evidence"]["end_entry"]
                if target == "snapshot":
                    final["companion_uuid"] = other
                elif target == "world":
                    final["world"]["subject_uuid"] = other
                elif target == "chain":
                    chain["subject_uuid"] = other
                else:
                    chain["entry"]["subject_uuid"] = other
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_end_entry_incomplete_observation_stale_hold_and_out_of_order_events_are_rejected(self):
        self.scenario = self.end_entry_scenario()
        for defect in ("gap", "critical", "stale", "short_hold", "out_of_order", "subject_missing", "deaths",
                       "health", "food", "source_block"):
            with self.subTest(defect=defect):
                evidence = self.end_entry_snapshots()
                final = evidence["final_snapshot"]
                chain = final["world"]["evidence"]["end_entry"]
                if defect == "gap":
                    chain["observation_gaps"] = 1
                elif defect == "critical":
                    chain["critical_evidence_complete"] = False
                elif defect == "stale":
                    chain["last_verified_game_tick"] = 59
                elif defect == "short_hold":
                    final["world"]["end_alive_held_ticks"] = 19
                elif defect == "out_of_order":
                    chain["activation"]["game_tick"] = 31
                elif defect == "subject_missing":
                    final["subject_present"] = False
                elif defect == "deaths":
                    final["deaths"] = 1
                elif defect in {"health", "food"}:
                    final[defect] = 15
                else:
                    chain["entry"]["source_block"] = {"x": 0, "y": 0, "z": 0}
                self.write_result(**evidence)
                with self.assertRaises(runner.PreflightError):
                    runner.validate_result(self.output, self.scenario)

    def test_end_entry_diagnostic_event_truncation_preserves_complete_critical_chain(self):
        self.scenario = self.end_entry_scenario()
        evidence = self.end_entry_snapshots()
        evidence["final_snapshot"]["world"]["evidence"].update(events=[], events_total=200, events_truncated=True)
        expected = self.write_result(**evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_end_entry_first_death_remains_failure_with_removed_body(self):
        self.scenario = self.end_entry_scenario()
        evidence = self.end_entry_snapshots()
        evidence["final_snapshot"] = {"alive": False, "subject_present": False, "deaths": 1}
        expected = self.write_result(status="failed", reason="companion_died", success_held_ticks=0, **evidence)
        self.assertEqual(expected, runner.validate_result(self.output, self.scenario))

    def test_end_entry_idle_client_calibration_requires_mixed_start_and_no_portal_chain(self):
        _, result = self.end_resources_negative_evidence()
        scenario = self.end_entry_scenario()
        snapshots = self.end_entry_snapshots()
        for field in ("initial_snapshot", "final_snapshot"):
            snapshot = copy.deepcopy(snapshots["initial_snapshot"])
            snapshot["inventory"] = dict(scenario["setup"]["inventory"])
            snapshot["inventory"].update({item: 1 for item in scenario["setup"]["equipment"].values()})
            snapshot["world"].update(copy.deepcopy(result[field]["world"]))
            snapshot["world"]["evidence"]["end_entry"] = snapshots["initial_snapshot"]["world"]["evidence"]["end_entry"]
            snapshot["world"].update(ender_eyes=8, ender_pearls=8, blaze_rods=4, eye_equivalents=16)
            result[field] = snapshot
        self.assertTrue(calibrator.end_entry_negative_check(0, result, scenario)["passed"])
        for defect in ("different_subject", "wrong_resources", "portal_progress", "gap", "replanning"):
            with self.subTest(defect=defect):
                invalid = copy.deepcopy(result)
                final = invalid["final_snapshot"]
                if defect == "different_subject":
                    final["companion_uuid"] = "484ef3c7-c6a6-441d-9958-bc4642337723"
                elif defect == "wrong_resources":
                    final["inventory"]["minecraft:ender_eye"] = 7
                elif defect == "portal_progress":
                    final["world"]["native_end_entry"] = True
                elif defect == "gap":
                    final["world"]["evidence"]["end_entry"]["observation_gaps"] = 1
                else:
                    final["world"]["evidence"]["coverage"]["risk_replanning"]["status"] = "observed"
                self.assertFalse(calibrator.end_entry_negative_check(0, invalid, scenario)["passed"])

    def test_result_version_must_match_requested_scenario(self):
        for version, other in (("live-v1", "survival-v1"), ("survival-v1", "live-v1"),
                               ("survival-v2", "survival-v1"), ("survival-v1", "survival-v2"),
                               ("expedition-v1", "survival-v2"), ("survival-v2", "expedition-v1"),
                               ("end-resources-v1", "expedition-v1"), ("expedition-v1", "end-resources-v1")):
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
