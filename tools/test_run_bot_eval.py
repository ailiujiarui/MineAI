"""Checks for benchmark evidence validation and reproducible source snapshots."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("run_bot_eval", Path(__file__).with_name("run_bot_eval.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class EvidenceValidationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.raw = Path(temporary.name)
        self.cases = [{"case_id": "planning.sample", "layer": "planning", "split": "dev", "group": "stock"}]

    @staticmethod
    def observation(**changes):
        row = {"case_id": "planning.sample", "evaluation_version": "bot-v1", "layer": "planning",
               "status": "ok", "success": True, "metrics": {}, "reason": "Exact stock verified",
               "evidence": {"final_inventory": {"minecraft:oak_planks": 4}}}
        row.update(changes)
        return row

    def collect(self, observations):
        (self.raw / "planning.jsonl").write_text(
            "".join(json.dumps(row) + "\n" for row in observations), encoding="utf-8")
        return runner.collect(self.cases, self.raw, {"planning"}, "bot-v1")

    @staticmethod
    def summary(rows, errors):
        return runner.summarize(rows, errors, [{"exit_code": 0}])

    def assert_invalid_observation(self, row, expected_error):
        rows, errors = self.collect([row])
        self.assertTrue(any(expected_error in error for error in errors), errors)
        self.assertEqual("infra_error", rows[0]["status"])
        self.assertFalse(self.summary(rows, errors)["valid_run"])

    def test_success_and_business_failure_both_are_scored(self):
        for success in (True, False):
            with self.subTest(success=success):
                rows, errors = self.collect([self.observation(success=success)])
                summary = self.summary(rows, errors)
                self.assertTrue(summary["valid_run"])
                self.assertEqual([], errors)
                self.assertEqual(1, summary["layers"]["planning"]["scored"])
                self.assertEqual(int(success), summary["layers"]["planning"]["passed"])
                self.assertEqual(float(success), summary["layers"]["planning"]["observed_success_rate"])

    def test_missing_selected_case_is_not_silently_dropped(self):
        rows, errors = self.collect([])
        summary = self.summary(rows, errors)
        self.assertFalse(summary["valid_run"])
        self.assertEqual("infra_error", rows[0]["status"])
        self.assertEqual(0, summary["layers"]["planning"]["scored"])
        self.assertIsNone(summary["layers"]["planning"]["observed_success_rate"])

    def test_unknown_case_is_invalid(self):
        self.assert_invalid_observation(self.observation(case_id="planning.unknown"), "unexpected case")

    def test_duplicate_case_invalidates_run(self):
        row = self.observation()
        rows, errors = self.collect([row, row])
        self.assertTrue(any("duplicate case" in error for error in errors))
        self.assertFalse(self.summary(rows, errors)["valid_run"])

    def test_wrong_and_missing_version_are_invalid(self):
        for version in ("bot-v0", None):
            with self.subTest(version=version):
                row = self.observation(evaluation_version=version)
                if version is None:
                    del row["evaluation_version"]
                self.assert_invalid_observation(row, "evaluation_version")

    def test_invalid_status_and_inconsistent_system_failure_are_invalid(self):
        self.assert_invalid_observation(self.observation(status="passed"), "invalid status")
        self.assert_invalid_observation(self.observation(status="system_failure", success=True),
                                        "system_failure cannot succeed")

    def test_scored_rows_require_boolean_success(self):
        for value in (None, 1, "true"):
            with self.subTest(value=value):
                self.assert_invalid_observation(self.observation(success=value), "boolean success")

    def test_wrong_layer_is_invalid(self):
        self.assert_invalid_observation(self.observation(layer="response"), "wrong layer")

    def test_non_object_json_is_invalid(self):
        for row in ([], None, "success", 1):
            with self.subTest(row=row):
                self.assert_invalid_observation(row, "JSON object")

    def test_metrics_reason_and_evidence_are_required(self):
        for changes, error in [({"metrics": []}, "metrics/reason"),
                               ({"reason": "  "}, "metrics/reason"),
                               ({"evidence": {}}, "missing evidence"),
                               ({"evidence": None}, "missing evidence")]:
            with self.subTest(changes=changes):
                self.assert_invalid_observation(self.observation(**changes), error)

    def test_unselected_case_remains_not_run(self):
        self.cases.append({"case_id": "live.sample", "layer": "live", "split": "dev",
                           "group": "world", "blocked_by": "Live endpoint absent"})
        rows, errors = self.collect([self.observation()])
        self.assertEqual("not_run", rows[1]["status"])
        self.assertIsNone(rows[1]["success"])
        self.assertTrue(self.summary(rows, errors)["valid_run"])

    def test_nonzero_command_invalidates_otherwise_complete_records(self):
        rows, errors = self.collect([self.observation()])
        summary = runner.summarize(rows, errors, [{"exit_code": 1}])
        self.assertFalse(summary["valid_run"])


class SourceSnapshotTest(unittest.TestCase):
    def test_untracked_source_is_included_and_additions_and_deletions_change_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = root / "agent/src/main/java/Original.java"
            added = original.with_name("Added.java")
            evaluator = root / "runner.py"
            original.parent.mkdir(parents=True)
            original.write_text("class Original {}", encoding="utf-8")
            evaluator.write_text("# evaluator", encoding="utf-8")
            tracked_name = original.relative_to(root).as_posix()
            added_name = added.relative_to(root).as_posix()
            with patch.object(runner, "ROOT", root), patch.object(runner.subprocess, "check_output") as listing:
                listing.return_value = (tracked_name + "\0").encode()
                before = runner.source_files_for([evaluator])
                before_hash = runner.digest_files(before)
                self.assertIn("--others", listing.call_args.args[0])
                self.assertIn("--exclude-standard", listing.call_args.args[0])
                added.write_text("class Added {}", encoding="utf-8")
                listing.return_value = (tracked_name + "\0" + added_name + "\0").encode()
                after_add = runner.source_files_for([evaluator])
                self.assertIn(added, after_add)
                self.assertNotEqual(before_hash, runner.digest_files(after_add))
                original.unlink()
                after_delete = runner.source_files_for([evaluator])
                self.assertNotIn(original, after_delete)
                self.assertNotEqual(before_hash, runner.digest_files(after_delete))


if __name__ == "__main__":
    unittest.main()
