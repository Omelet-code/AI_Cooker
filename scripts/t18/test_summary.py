"""仅算法替身样本，不写入gold或正式观测，不能当产品质量证据。"""

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from summarize import main, summarize, unique_object


def sample(kind="retrieval", **changes):
    row = {"id": "fixture-1", "kind": kind, "mode": "ISOLATED_SUBSTITUTE", "path": "fixture-service",
           "environment": "algorithm-fixture-only", "retrievalMode": "HYBRID", "warmth": "WARM",
           "outcome": "SUCCEEDED", "durationMs": 10}
    if kind == "retrieval":
        row.update(goldStatus="REVIEWED", goldScope="POOL", goldEvidenceRef="synthetic-unit-fixture-not-new-db",
                   goldRecipeIds=["1", "2"], rankedRecipeIds=["8", "1", "2"])
    row.update(changes)
    return row


def run(*rows):
    return summarize({"schemaVersion": 1, "samples": list(rows)})


class SummaryTest(unittest.TestCase):
    def test_zero_has_no_quality_or_percentile(self):
        self.assertEqual(run()["sampleN"], 0)
        self.assertEqual(run()["measurementStatus"], "NO_SAMPLES")
        self.assertEqual(run()["groups"], [])

    def test_unreviewed_gold_produces_no_metrics(self):
        metric = run(sample(goldStatus="PENDING", goldScope=None, goldRecipeIds=None))["groups"][0]["retrieval"]
        self.assertEqual(metric["pendingGoldQueries"], 1)
        self.assertIsNone(metric["mrr"])
        self.assertIsNone(metric["hitAt5"]["rate"])

    def test_failed_retrieval_stays_in_denominator(self):
        rows = [sample(), sample(id="fixture-failed", outcome="FAILED", rankedRecipeIds=None)]
        group = run(*rows)["groups"][0]
        self.assertEqual(group["retrieval"]["positiveGoldDenominator"], 2)
        self.assertEqual(group["retrieval"]["hitAt5"]["rate"], .5)
        self.assertEqual(group["retrieval"]["mrr"], .25)
        self.assertEqual(group["retrieval"]["recallAt10"]["macroMean"], .5)
        self.assertEqual(group["latency"]["failedOrTimedOutTimesMs"], [10])

    def test_unknown_result_cannot_be_coerced_to_failure_or_success(self):
        metric = run(sample(outcome="UNKNOWN", rankedRecipeIds=None))["groups"][0]["retrieval"]
        self.assertEqual(metric["unknownResultQueries"], 1)
        self.assertIsNone(metric["mrr"])
        self.assertIsNone(metric["hitAt10"]["rate"])

    def test_empty_gold_uses_separate_no_result_denominator(self):
        metric = run(sample(goldRecipeIds=[], rankedRecipeIds=[], outcome="NO_MATCH"))["groups"][0]["retrieval"]
        self.assertEqual(metric["positiveGoldDenominator"], 0)
        self.assertIsNone(metric["hitAt5"]["rate"])
        self.assertEqual(metric["reviewedNoResult"]["rate"], 1)

    def test_percentile_requires_thirty_same_group_successes(self):
        rows = [sample(id=f"fixture-{i}", durationMs=i) for i in range(1, 31)]
        self.assertIsNone(run(*rows[:29])["groups"][0]["latency"]["p95Ms"])
        self.assertEqual(run(*rows)["groups"][0]["latency"]["p95Ms"], 29)
        failure = sample(id="failure-long", outcome="TIMEOUT", rankedRecipeIds=None, durationMs=99999)
        self.assertEqual(run(*rows, failure)["groups"][0]["latency"]["p95Ms"], 29)

    def test_modes_paths_coldness_and_gold_scopes_are_not_combined(self):
        rows = [sample(id="a"), sample(id="b", mode="REAL_SUPPLIER"), sample(id="c", warmth="COLD"),
                sample(id="d", path="other-service"), sample(id="e", goldScope="COMPLETE"),
                sample(id="f", retrievalMode="LEXICAL_FALLBACK")]
        self.assertEqual(len(run(*rows)["groups"]), 6)
        self.assertTrue(all(g["latency"]["p95Ms"] is None for g in run(*rows)["groups"]))

    def test_meal_request_denominator_is_distinct_from_options(self):
        option = {"roleQuantitySatisfied": True, "deduplicated": True, "hardViolations": 0}
        result = run(sample("meal", options=[option, option], differentSets=True),
                     sample("meal", id="no-match", outcome="NO_MATCH", options=[]))["groups"][0]
        self.assertEqual(result["requestDenominator"], 2)
        self.assertEqual(result["observedSuccessRate"], .5)
        self.assertEqual(result["outcomeRates"]["NO_MATCH"]["rate"], .5)
        self.assertEqual(result["meal"]["optionDenominator"], 2)
        self.assertEqual(result["meal"]["twoOptionDifference"]["denominator"], 1)

    def test_shopping_overall_and_eligible_coverage_and_unknown_cost(self):
        lines = [{"quantityCalculable": True, "matchEligible": True, "matched": True, "costCalculable": True},
                 {"quantityCalculable": False, "matchEligible": False, "matched": False, "costCalculable": False, "reasonCode": "QUANTITY_PENDING"}]
        metric = run(sample("shopping", mode="LOCAL_MOCK", lines=lines))["groups"][0]["shopping"]
        self.assertEqual(metric["overallMatchCoverage"]["rate"], .5)
        self.assertEqual(metric["eligibleMatchCoverage"]["rate"], 1)
        self.assertEqual(metric["reasonCounts"]["QUANTITY_PENDING"], 1)
        self.assertIsNone(metric["consumptionCost"])
        self.assertIsNone(metric["purchaseOutlay"])

    def test_usage_unknown_is_not_free_and_safety_unknown_is_not_zero(self):
        metric = run(sample("calls", methodAttempts=1, httpAttempts=None, inputTokens=None, outputTokens=None))["groups"][0]
        self.assertEqual(metric["calls"]["methodAttempts"]["total"], 1)
        self.assertIsNone(metric["calls"]["httpAttempts"]["total"])
        self.assertEqual(metric["calls"]["unknownUsageRate"], 1)
        self.assertIsNone(metric["calls"]["paidAmount"])
        self.assertIsNone(metric["safety"]["factMutations"]["total"])

    def test_human_rating_requires_explicit_review_and_unknown_remains(self):
        self.assertRaises(ValueError, run, sample("guidance", ratings={"faithfulness": 5}))
        result = run(sample("guidance", ratingsSource="USER", recipeIds=["1"], ratings={"faithfulness": 4}))["groups"][0]
        metric = result["humanRatings"]
        self.assertEqual(result["ratingReferences"][0]["recipeIds"], ["1"])
        self.assertEqual(metric["faithfulness"]["mean"], 4)
        self.assertIsNone(metric["tone"]["mean"])

    def test_rejects_duplicate_observations_invalid_numbers_and_unsafe_fields(self):
        self.assertRaises(ValueError, run, sample(), sample())
        self.assertRaises(ValueError, run, sample(durationMs=float("nan")))
        self.assertRaises(ValueError, run, sample(durationMs=True))
        self.assertRaises(ValueError, run, sample(apiKey="test-only-never-valid"))
        self.assertRaises(ValueError, run, sample(rankedRecipeIds=["1", "1"]))
        self.assertRaises(ValueError, unique_object, [("samples", []), ("samples", [])])

    def test_cli_creates_only_new_output_and_does_not_change_input(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "input.json", Path(directory) / "result.json"
            source.write_text(json.dumps({"schemaVersion": 1, "samples": []}), encoding="utf-8")
            before = source.read_text(encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(main(["--input", str(source), "--output", str(target)]), 0)
                self.assertEqual(main(["--input", str(source), "--output", str(target)]), 1)
            self.assertEqual(source.read_text(encoding="utf-8"), before)
            self.assertEqual(json.loads(target.read_text(encoding="utf-8"))["sampleN"], 0)


if __name__ == "__main__":
    unittest.main()
