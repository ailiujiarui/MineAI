import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import paired_report as report


def dataset():
    candidate = {"provider": "openai", "model": "example-model", "settings_id": "settings-v1"}
    manifest = {
        "type": "manifest", "schema_version": 1, "run_id": "trial-1",
        "revision": "revision-and-dirty-tree-fingerprint", "candidate": candidate,
        "wall_time_ms": 5000,
        "cases": [{"case_id": case, "fixture": case + "-v1", "seeds": [10, 20, 30]}
                  for case in ("gather", "place", "find")],
    }
    records = [manifest]
    for case in manifest["cases"]:
        for rep, seed in enumerate(case["seeds"], 1):
            for variant in report.VARIANTS:
                records.append({
                    "type": "result", **{key: copy.deepcopy(manifest[key]) for key in report.IDENTITY},
                    "case_id": case["case_id"], "fixture": case["fixture"],
                    "rep": rep, "seed": seed, "variant": variant,
                    "status": "success", "reason": None, "judge_passed": True,
                    "elapsed_ms": 100, "input_tokens": 10, "output_tokens": 5,
                    "llm_calls": 1, "tool_calls": 2,
                })
    return records


def status(row, value):
    row["status"] = value
    row["reason"] = None if value == "success" else "deadline" if value == "not_run" else "test_reason"
    row["judge_passed"] = True if value == "success" else False if value == "failure" else None
    if value == "not_run":
        row["elapsed_ms"] = None
        for key in report.COUNTERS:
            row[key] = 0


class PairedReportTest(unittest.TestCase):
    def test_complete_success(self):
        result = report.build_report(dataset())
        self.assertEqual(result["paired"]["both_success"], 9)
        self.assertEqual(result["paired"]["on_share_of_discordant"], report.rate(0, 0))
        self.assertEqual(result["wall_time_ms"], 5000)
        self.assertEqual(result["attempt_elapsed_total_ms"], 1800)
        self.assertEqual(result["variants"]["verify_on"]["success_per_scheduled"], report.rate(9, 9))
        self.assertEqual(result["variants"]["verify_on"]["usage_totals"]["input_tokens"], 90)

    def test_failure_runtime_and_discordance(self):
        data = dataset()
        for index in (2, 4, 6):
            status(data[index], "failure")
            data[index]["elapsed_ms"] = 900
        result = report.build_report(data)
        paired = result["paired"]
        self.assertEqual(paired["on_only_success"], 3)
        self.assertEqual(paired["mcnemar_exact_two_sided_p"], 0.25)
        self.assertIsNone(paired["on_to_off_discordant_ratio"])
        self.assertEqual(paired["success_rate_delta_on_minus_off"], 1 / 3)
        off = result["variants"]["verify_off"]
        self.assertEqual(off["attempt_elapsed_total_ms"], 3300)
        self.assertEqual(off["success_elapsed_mean_ms"], 100)
        self.assertEqual(off["success_per_scheduled"], report.rate(6, 9))
        self.assertEqual(off["reason_counts"]["failure"], {"test_reason": 3})
        self.assertEqual(len(result["results"]), 18)

    def test_infra_and_not_run_remain_separate(self):
        data = dataset()
        status(data[1], "not_run")
        status(data[3], "infra_error")
        result = report.build_report(data)
        on = result["variants"]["verify_on"]
        self.assertEqual(on["success_per_scheduled"], report.rate(7, 9))
        self.assertEqual(on["success_per_judged"], report.rate(7, 7))
        self.assertEqual(on["status_counts"]["infra_error"], 1)
        self.assertEqual(on["status_counts"]["not_run"], 1)
        self.assertEqual(on["usage_totals"]["llm_calls"], 8)
        self.assertEqual(result["paired"]["eligible_pairs"], 7)
        self.assertEqual(result["paired"]["excluded_pairs"], 2)

    def test_no_run_is_not_success(self):
        data = dataset()
        for row in data[1:]:
            status(row, "not_run")
        result = report.build_report(data)
        on = result["variants"]["verify_on"]
        self.assertEqual(on["success_per_scheduled"], report.rate(0, 9))
        self.assertEqual(on["success_per_judged"], report.rate(0, 0))
        self.assertIsNone(on["success_elapsed_mean_ms"])
        self.assertIsNone(result["paired"]["mcnemar_exact_two_sided_p"])

    def test_both_directions_and_paired_time(self):
        data = dataset()
        status(data[2], "failure")
        status(data[3], "failure")
        data[5]["elapsed_ms"] = 160
        result = report.build_report(data)["paired"]
        self.assertEqual(result["on_to_off_discordant_ratio"], 1)
        self.assertEqual(result["on_share_of_discordant"], report.rate(1, 2))
        self.assertEqual(result["both_success_elapsed_delta_mean_ms"], 60 / 7)

    def test_incomplete_duplicate_and_missing_entire_case(self):
        for data in (dataset()[:-1], dataset() + [dataset()[1]], dataset()[:13]):
            with self.subTest(length=len(data)), self.assertRaises(report.ValidationError):
                report.build_report(data)

    def test_pair_identity_must_match_manifest(self):
        for key, value in (("seed", 99), ("fixture", "other"), ("revision", "other"),
                           ("run_id", "other"), ("candidate", {"provider": "other"}),
                           ("schema_version", 2), ("case_id", "other"),
                           ("rep", 4), ("variant", "unknown")):
            data = dataset()
            data[1][key] = value
            with self.subTest(key=key), self.assertRaises(report.ValidationError):
                report.build_report(data)

    def test_missing_unknown_and_null_metrics(self):
        for mutation in (lambda row: row.pop("tool_calls"),
                         lambda row: row.update(extra=1),
                         lambda row: row.update(input_tokens=None)):
            data = dataset()
            mutation(data[1])
            with self.assertRaises(report.ValidationError):
                report.build_report(data)

    def test_invalid_numeric_values(self):
        for key, value in (("elapsed_ms", float("nan")), ("elapsed_ms", float("inf")),
                           ("elapsed_ms", -1), ("elapsed_ms", True),
                           ("input_tokens", True), ("llm_calls", 1.5), ("seed", True)):
            data = dataset()
            data[1][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(report.ValidationError):
                report.build_report(data)

    def test_success_requires_external_confirmation(self):
        for value in (None, False, 1):
            data = dataset()
            data[1]["judge_passed"] = value
            with self.assertRaises(report.ValidationError):
                report.build_report(data)

    def test_invalid_status_combinations(self):
        for changes in ({"status": "unknown"}, {"reason": "failed"},
                        {"status": "failure", "reason": "timeout"},
                        {"status": "infra_error", "reason": "network"},
                        {"status": "not_run", "reason": "deadline", "judge_passed": None}):
            data = dataset()
            data[1].update(changes)
            with self.assertRaises(report.ValidationError):
                report.build_report(data)

    def test_manifest_shape(self):
        for changes in ({"schema_version": True}, {"schema_version": 2},
                        {"cases": []}, {"wall_time_ms": None}, {"candidate": {}},
                        {"run_id": ""}, {"unknown": 1}):
            data = dataset()
            data[0].update(changes)
            with self.assertRaises(report.ValidationError):
                report.build_report(data)
        data = dataset()
        data[0]["cases"][1] = data[0]["cases"][0]
        with self.assertRaises(report.ValidationError):
            report.build_report(data)

    def test_cli_and_strict_jsonl(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in dataset()) + "\n", encoding="utf-8")
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                self.assertEqual(report.main([str(path)]), 0)
            self.assertEqual(json.loads(stdout.getvalue())["paired"]["eligible_pairs"], 9)
            for invalid in ('{"type":"manifest","type":"result"}\n', "\n", "secret-invalid-json\n"):
                path.write_text(invalid, encoding="utf-8")
                stderr = io.StringIO()
                with redirect_stderr(stderr):
                    self.assertEqual(report.main([str(path)]), 2)
                self.assertNotIn("secret-invalid-json", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
