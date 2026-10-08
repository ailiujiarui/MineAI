"""Strict paired verify evaluation report, using only the Python standard library.

JSONL contract v1 (first line manifest, remaining lines results):
  manifest: type, schema_version=1, run_id, revision, candidate, wall_time_ms, cases
  candidate: provider, model, settings_id (non-secret fingerprint of effective settings)
  cases: exactly three {case_id, fixture, seeds: [seed_for_rep_1, rep_2, rep_3]}
  result: type="result", schema_version=1, run_id, revision, candidate, case_id,
          fixture, rep (1..3), seed, variant (verify_on/verify_off), status,
          reason, elapsed_ms, judge_passed, input_tokens, output_tokens,
          llm_calls, tool_calls

status is success/failure/not_run/infra_error. Success/failure require an external
server-side judge boolean; not_run/infra_error require judge_passed=null. Success
requires reason=null; all other statuses require a nonempty reason code. not_run
requires elapsed_ms=null and zero counters; every attempted run requires finite
elapsed_ms>=0 and known nonnegative counters (missing usage is not zero usage).
Elapsed time includes the whole attempt, including failed calls and final judging.
Token counters use provider prompt/completion usage, including all judging and
compaction LLM calls. Count attempted LLM/tool calls, including failed calls.
Every scheduled slot must have a result, even when the deadline prevents a run.
No credentials, prompts, or raw transport errors belong in this contract.
"""

import argparse
from collections import Counter
import json
import math
from pathlib import Path
import sys


VARIANTS = ("verify_on", "verify_off")
STATUSES = ("success", "failure", "not_run", "infra_error")
COUNTERS = ("input_tokens", "output_tokens", "llm_calls", "tool_calls")
IDENTITY = ("schema_version", "run_id", "revision", "candidate")
MANIFEST_KEYS = set(IDENTITY) | {"type", "wall_time_ms", "cases"}
RESULT_KEYS = set(IDENTITY) | {
    "type", "case_id", "fixture", "rep", "seed", "variant", "status",
    "reason", "elapsed_ms", "judge_passed", *COUNTERS,
}


class ValidationError(ValueError):
    """Input cannot support a complete, comparable paired report."""


def require(condition, message):
    if not condition:
        raise ValidationError(message)


def fields(value, expected, label):
    require(isinstance(value, dict) and set(value) == expected,
            f"{label}: missing or unknown fields")


def text(value):
    return isinstance(value, str) and bool(value.strip())


def integer(value):
    return type(value) is int


def duration(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON field")
        result[key] = value
    return result


def read_jsonl(path):
    records = []
    with Path(path).open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            require(bool(line.strip()), f"line {number}: empty record")
            try:
                records.append(json.loads(line, object_pairs_hook=unique_object))
            except (ValueError, RecursionError) as error:
                # Do not echo input: accidental credentials must not enter diagnostics.
                raise ValidationError(f"line {number}: invalid JSON record") from error
    return records


def validate(records):
    require(bool(records), "missing manifest")
    manifest = records[0]
    fields(manifest, MANIFEST_KEYS, "manifest")
    require(manifest["type"] == "manifest", "first record must be manifest")
    require(integer(manifest["schema_version"]) and manifest["schema_version"] == 1,
            "unsupported schema_version")
    for key in ("run_id", "revision"):
        require(text(manifest[key]), f"manifest: invalid {key}")
    candidate = manifest["candidate"]
    fields(candidate, {"provider", "model", "settings_id"}, "candidate")
    require(all(text(value) for value in candidate.values()), "invalid candidate")
    require(duration(manifest["wall_time_ms"]), "invalid wall_time_ms")
    cases = manifest["cases"]
    require(isinstance(cases, list) and len(cases) == 3, "expected exactly three cases")
    schedule = {}
    for case in cases:
        fields(case, {"case_id", "fixture", "seeds"}, "case")
        require(text(case["case_id"]) and text(case["fixture"]), "invalid case identity")
        require(case["case_id"] not in schedule, "duplicate case_id")
        seeds = case["seeds"]
        require(isinstance(seeds, list) and len(seeds) == 3
                and all(integer(seed) for seed in seeds), "expected three integer seeds")
        schedule[case["case_id"]] = case
    slots = {}
    for index, row in enumerate(records[1:], 2):
        label = f"record {index}"
        fields(row, RESULT_KEYS, label)
        require(row["type"] == "result", f"{label}: expected result")
        require(integer(row["schema_version"]), f"{label}: invalid schema_version")
        require(all(row[key] == manifest[key] for key in IDENTITY),
                f"{label}: mixed run/version/model/settings")
        require(text(row["case_id"]) and row["case_id"] in schedule,
                f"{label}: unscheduled case")
        require(integer(row["rep"]) and 1 <= row["rep"] <= 3, f"{label}: invalid rep")
        require(row["variant"] in VARIANTS, f"{label}: invalid variant")
        case = schedule[row["case_id"]]
        require(row["fixture"] == case["fixture"], f"{label}: fixture mismatch")
        require(integer(row["seed"]) and row["seed"] == case["seeds"][row["rep"] - 1],
                f"{label}: seed mismatch")
        require(row["status"] in STATUSES, f"{label}: invalid status")
        for key in COUNTERS:
            require(integer(row[key]) and row[key] >= 0, f"{label}: invalid {key}")
        status = row["status"]
        if status == "success":
            require(row["reason"] is None and row["judge_passed"] is True,
                    f"{label}: success requires server judge confirmation")
        elif status == "failure":
            require(text(row["reason"]) and row["judge_passed"] is False,
                    f"{label}: failure requires reason and server judge rejection")
        else:
            require(text(row["reason"]) and row["judge_passed"] is None,
                    f"{label}: unjudged status requires reason and null judge")
        if status == "not_run":
            require(row["elapsed_ms"] is None and all(row[key] == 0 for key in COUNTERS),
                    f"{label}: not_run cannot have runtime or calls/tokens")
        else:
            require(duration(row["elapsed_ms"]), f"{label}: invalid elapsed_ms")
        key = (row["case_id"], row["rep"], row["variant"])
        require(key not in slots, f"{label}: duplicate slot")
        slots[key] = row
    expected = {(case_id, rep, variant) for case_id in schedule
                for rep in range(1, 4) for variant in VARIANTS}
    require(set(slots) == expected, "incomplete schedule: require all 18 result records")
    return manifest, slots


def rate(numerator, denominator):
    return {"n": numerator, "m": denominator,
            "rate": numerator / denominator if denominator else None}


def summarize(rows):
    counts = Counter(row["status"] for row in rows)
    attempted = [row for row in rows if row["status"] != "not_run"]
    successful = [row for row in rows if row["status"] == "success"]
    judged = counts["success"] + counts["failure"]
    return {
        "status_counts": {status: counts[status] for status in STATUSES},
        "success_per_scheduled": rate(counts["success"], len(rows)),
        "success_per_judged": rate(counts["success"], judged),
        "attempt_elapsed_total_ms": sum(row["elapsed_ms"] for row in attempted),
        "attempt_elapsed_mean_ms": (sum(row["elapsed_ms"] for row in attempted)
                                    / len(attempted) if attempted else None),
        "success_elapsed_mean_ms": (sum(row["elapsed_ms"] for row in successful)
                                    / len(successful) if successful else None),
        "reason_counts": {status: dict(sorted(Counter(
            row["reason"] for row in rows if row["status"] == status).items()))
            for status in ("failure", "infra_error", "not_run")},
        "usage_totals": {key: sum(row[key] for row in rows) for key in COUNTERS},
    }


def paired(rows):
    counts = Counter()
    excluded = Counter()
    elapsed_deltas = []
    for on, off in rows:
        if any(row["status"] not in ("success", "failure") for row in (on, off)):
            excluded[f'{on["status"]}/{off["status"]}'] += 1
            continue
        a, b = on["status"] == "success", off["status"] == "success"
        counts["both_success" if a and b else "on_only_success" if a
               else "off_only_success" if b else "both_failure"] += 1
        if a and b:
            elapsed_deltas.append(on["elapsed_ms"] - off["elapsed_ms"])
    wins, losses = counts["on_only_success"], counts["off_only_success"]
    discordant = wins + losses
    eligible = sum(counts.values())
    p_value = min(1.0, 2 * sum(math.comb(discordant, i)
                             for i in range(min(wins, losses) + 1)) / 2 ** discordant)
    return {
        **{key: counts[key] for key in ("both_success", "both_failure",
                                        "on_only_success", "off_only_success")},
        "eligible_pairs": eligible,
        "excluded_pairs": sum(excluded.values()),
        "excluded_status_pairs": dict(sorted(excluded.items())),
        "discordant_pairs": discordant,
        "on_share_of_discordant": rate(wins, discordant),
        "on_to_off_discordant_ratio": wins / losses if losses else None,
        "success_rate_delta_on_minus_off": (wins - losses) / eligible if eligible else None,
        "mcnemar_exact_two_sided_p": p_value if eligible else None,
        "both_success_elapsed_delta_mean_ms": (sum(elapsed_deltas) / len(elapsed_deltas)
                                                if elapsed_deltas else None),
    }


def build_report(records):
    manifest, slots = validate(records)
    rows = [slots[key] for key in sorted(slots)]
    pairs = [(slots[(case["case_id"], rep, "verify_on")],
              slots[(case["case_id"], rep, "verify_off")])
             for case in manifest["cases"] for rep in range(1, 4)]
    return {
        "schema_version": 1,
        "manifest": manifest,
        "wall_time_ms": manifest["wall_time_ms"],
        "attempt_elapsed_total_ms": sum(row["elapsed_ms"] for row in rows
                                        if row["status"] != "not_run"),
        "variants": {variant: summarize([row for row in rows if row["variant"] == variant])
                     for variant in VARIANTS},
        "paired": paired(pairs),
        "cases": {case["case_id"]: {
            "variants": {variant: summarize([row for row in rows
                         if row["case_id"] == case["case_id"] and row["variant"] == variant])
                         for variant in VARIANTS},
            "paired": paired([(on, off) for on, off in pairs
                              if on["case_id"] == case["case_id"]]),
        } for case in manifest["cases"]},
        "results": rows,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jsonl", help="complete manifest + 18 result records")
    args = parser.parse_args(argv)
    try:
        report = build_report(read_jsonl(args.jsonl))
    except (ValidationError, OSError, UnicodeError) as error:
        message = str(error) if isinstance(error, ValidationError) else "cannot read input"
        print(f"paired_report: {message}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
