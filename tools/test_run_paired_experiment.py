import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_paired_experiment as runner


class PairedLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.providers = self.root / "providers.json"
        self.providers.write_text(json.dumps({"entries": [{"id": "existing", "provider": "openai",
            "model": "example-model", "api_key": "secret-never-log"}]}), encoding="utf-8")

    def run_main(self, output, prepare=True):
        argv = ["runner", "--providers-file", str(self.providers), "--provider-id", "existing", "--output", str(output)]
        if prepare:
            argv.append("--prepare-only")
        with patch("sys.argv", argv), patch.object(runner, "fingerprint", return_value={"git_head": "head", "working_diff_sha256": "hash"}), contextlib.redirect_stdout(io.StringIO()):
            runner.main()

    def test_prepare_has_18_isolated_pairs_and_no_model_or_gradle(self):
        output = self.root / "prepared"
        with patch.object(runner.subprocess, "Popen") as popen:
            self.run_main(output)
            popen.assert_not_called()
        requests = [json.loads(p.read_text(encoding="utf-8")) for p in output.glob("*/request.json")]
        self.assertEqual(18, len(requests))
        for scenario in runner.SCENARIOS:
            for repeat in range(1, 4):
                pair = [r for r in requests if r["scenario"] == scenario and r["repeat"] == repeat]
                self.assertEqual({False, True}, {r["feedback"] for r in pair})
                for key in ("seed", "deadline_seconds", "max_calls", "max_tools", "fixture_version", "model"):
                    self.assertEqual(pair[0][key], pair[1][key])
        for file in output.rglob("*.json"):
            self.assertNotIn("secret-never-log", file.read_text(encoding="utf-8"))
        self.assertEqual(18, len(json.loads((output / "commands.json").read_text(encoding="utf-8"))))
        records = runner.paired_report.read_jsonl(output / "paired.jsonl")
        runner.paired_report.validate(records)
        self.assertEqual(18, len(records[1:]))
        self.assertTrue(all(r["status"] == "not_run" for r in records[1:]))
        self.assertFalse(json.loads((output / "manifest.json").read_text())["intervention"]["product_goal_steward_ablation"])

    def test_existing_output_refused_without_overwrite(self):
        output = self.root / "existing"
        output.mkdir()
        sentinel = output / "world.dat"
        sentinel.write_text("keep", encoding="utf-8")
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.run_main(output)
        self.assertEqual("keep", sentinel.read_text(encoding="utf-8"))

    def test_missing_server_result_is_failure_in_full_denominator(self):
        with patch.object(runner.subprocess, "Popen") as popen:
            popen.return_value.returncode = 1
            popen.return_value.stdout = io.BytesIO(b"process failure secret-never-log\n")
            self.run_main(self.root / "failed", prepare=False)
        self.assertEqual(18, popen.call_count)
        records = runner.paired_report.read_jsonl(self.root / "failed" / "paired.jsonl")
        self.assertEqual(19, len(records))
        for row in records[1:]:
            self.assertEqual("infra_error", row["status"])
            self.assertIsNone(row["input_tokens"])
            self.assertIsNone(row["judge_passed"])
        with self.assertRaises(runner.paired_report.ValidationError):
            runner.paired_report.validate(records)
        self.assertFalse((self.root / "failed" / "summary.json").exists())

    def test_known_usage_exports_exact_schema_and_cached_prompt_totals(self):
        output = self.root / "known"
        def server_result(command, **kwargs):
            request_path = Path(next(x.split("=", 1)[1] for x in command if x.startswith("-PexperimentRequest=")))
            trial = request_path.parent
            runner.save(trial / "result.json", {"success": True, "kind": "completed", "calls": 2, "tools": 1,
                        "usage_complete": True, "usage": {"input": 10, "output": 5, "cacheRead": 20, "cacheWrite": 3}})
            from unittest.mock import Mock
            return Mock(returncode=0, stdout=io.BytesIO(b"server exited\n"))
        with patch.object(runner.subprocess, "Popen", side_effect=server_result):
            self.run_main(output, prepare=False)
        records = runner.paired_report.read_jsonl(output / "paired.jsonl")
        report = runner.paired_report.build_report(records)
        self.assertEqual(9, report["paired"]["both_success"])
        self.assertEqual(33, records[1]["input_tokens"])
        self.assertEqual(5, records[1]["output_tokens"])

    def test_source_change_blocks_remaining_runs(self):
        output = self.root / "changed"
        argv = ["runner", "--providers-file", str(self.providers), "--provider-id", "existing", "--output", str(output)]
        original = {"git_head": "head", "working_diff_sha256": "hash"}
        with patch("sys.argv", argv), patch.object(runner, "fingerprint", side_effect=[original] +
                [{"git_head": "head", "working_diff_sha256": "changed"}] * 18), \
                patch.object(runner.subprocess, "Popen") as popen, contextlib.redirect_stdout(io.StringIO()):
            runner.main()
        popen.assert_not_called()
        records = runner.paired_report.read_jsonl(output / "paired.jsonl")
        runner.paired_report.validate(records)
        self.assertTrue(all(row["reason"] == "source_or_configuration_changed" for row in records[1:]))

    def test_streaming_redaction_across_chunks(self):
        class Chunks(io.BytesIO):
            def read1(self, size):
                return self.read(3)
        errors = []
        path = self.root / "launcher.log"
        runner.capture_log(Chunks(b"before secret-never-log after\n"), path, ["secret-never-log"], errors)
        self.assertEqual(b"before [redacted] after\n", path.read_bytes())
        self.assertEqual([], errors)

    def test_diagnostic_trial_limit_retains_all_slots_and_exit_evidence(self):
        output = self.root / "diagnostic"
        argv = ["runner", "--providers-file", str(self.providers), "--provider-id", "existing",
                "--output", str(output), "--trial-limit", "1"]
        from unittest.mock import Mock
        with patch("sys.argv", argv), patch.object(runner, "fingerprint", return_value={"git_head": "head", "working_diff_sha256": "hash"}), \
                patch.object(runner.subprocess, "Popen", return_value=Mock(returncode=7,
                    stdout=io.BytesIO(b"failure secret-never-log\n"))) as popen, contextlib.redirect_stdout(io.StringIO()):
            runner.main()
        self.assertEqual(1, popen.call_count)
        records = runner.paired_report.read_jsonl(output / "paired.jsonl")[1:]
        self.assertEqual(18, len(records))
        self.assertEqual(17, sum(row["status"] == "not_run" for row in records))
        attempted = next(row for row in records if row["status"] != "not_run")
        self.assertEqual("process_exit_no_server_result", attempted["reason"])
        trial = output / "collect-1-off"
        self.assertEqual(7, json.loads((trial / "launcher-status.json").read_text())["exit_code"])
        self.assertNotIn("secret-never-log", (trial / "launcher.log").read_text())


if __name__ == "__main__":
    unittest.main()
