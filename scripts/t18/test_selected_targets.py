"""人工目标口径的算法替身测试；不生成正式质量成绩。"""
import copy
from pathlib import Path
import unittest
from selected_targets import load, validate_targets, join_and_summarize
from test_summary import sample, run

TARGETS = load(Path(__file__).resolve().parents[2] / "backend/src/test/resources/t18/retrieval-targets-v1.json")


def observation(case, **changes):
    row = sample(id="fixture-" + case, caseId=case)
    for key in list(row):
        if key.startswith("gold"):
            del row[key]
    row.update(changes)
    return row


def joined(*rows):
    return join_and_summarize(TARGETS, dict(schemaVersion=1, samples=list(rows)))


class SelectedTargetsTest(unittest.TestCase):
    def test_frozen_identity_and_counts(self):
        self.assertEqual(len(validate_targets(TARGETS)), 20)
        for mutation in ("scoring", "references", "count"):
            bad = copy.deepcopy(TARGETS)
            if mutation == "scoring": bad["queries"][17]["scoringMode"] = "EMPTY_RESULT"
            elif mutation == "references": bad["queries"][0]["targetReferences"][0]["recipeId"] = "1"
            else: bad["metricPolicy"]["positiveQueryDenominator"] = 16
            with self.assertRaises(ValueError): validate_targets(bad)

    def test_zero_stays_zero(self):
        self.assertEqual(joined()["groups"], [])
        self.assertEqual(joined()["sampleN"], 0)

    def test_empty_eligibility_requires_actual_zero_calls(self):
        result = joined(observation("Q19", retrievalMode="NOT_APPLICABLE", outcome="NO_MATCH",
                                   rankedRecipeIds=[], methodAttempts=0, httpAttempts=0))
        empty = next(g for g in result["groups"] if g["goldScope"] == "SELECTED_TARGET_EMPTY")
        self.assertEqual(empty["retrievalMode"], "NOT_APPLICABLE")
        self.assertEqual(empty["retrieval"]["reviewedNoResult"]["rate"], 1)
        with self.assertRaises(ValueError): joined(observation("Q19", retrievalMode="NOT_APPLICABLE", outcome="NO_MATCH", rankedRecipeIds=[]))

    def test_fixed_seventeen_one_and_no_recall(self):
        rows = [observation(f"Q{i:02}", rankedRecipeIds=TARGETS["queries"][i-1]["targetRecipeIds"]) for i in range(1, 18)]
        rows.append(observation("Q19", rankedRecipeIds=[], outcome="NO_MATCH"))
        result = joined(*rows)
        positive = next(g for g in result["groups"] if g["goldScope"] == "SELECTED_TARGET")
        self.assertEqual(positive["retrieval"]["hitAt5"]["denominator"], 17)
        self.assertEqual(positive["retrieval"]["hitAt5"]["rate"], 1)
        self.assertNotIn("recallAt5", positive["retrieval"])
        self.assertNotIn("recallAt10", positive["retrieval"])
        self.assertIsNone(positive["latency"]["p95Ms"])
        empty = next(g for g in result["groups"] if g["goldScope"] == "SELECTED_TARGET_EMPTY")
        self.assertEqual(empty["retrieval"]["reviewedNoResult"]["denominator"], 1)

    def test_missing_unknown_and_excluded_not_negative(self):
        result = joined(observation("Q01", rankedRecipeIds=["358"]))
        metric = next(g for g in result["groups"] if g["goldScope"] == "SELECTED_TARGET")["retrieval"]
        self.assertEqual(metric["positiveGoldDenominator"], 17)
        self.assertEqual(metric["unknownResultQueries"], 16)
        self.assertIsNone(metric["mrr"])
        for case in ("Q18", "Q20"):
            with self.assertRaises(ValueError): joined(observation(case))

    def test_failure_timeout_zero_and_truncated_first_target(self):
        metric = run(sample(goldScope="SELECTED_TARGET", goldRecipeIds=["139", "154"], rankedRecipeIds=["5", "154", "139"]),
                     sample(id="failed", goldScope="SELECTED_TARGET", outcome="FAILED", rankedRecipeIds=None),
                     sample(id="timeout", goldScope="SELECTED_TARGET", outcome="TIMEOUT", rankedRecipeIds=None))["groups"][0]["retrieval"]
        self.assertAlmostEqual(metric["mrr"], 1/6)
        self.assertEqual(metric["hitAt5"]["rate"], 1/3)
        self.assertNotIn("recallAt5", metric)

    def test_duplicate_case_and_label_injection_rejected(self):
        with self.assertRaises(ValueError): joined(observation("Q01"), observation("Q01", id="different"))
        with self.assertRaises(ValueError): joined(observation("Q01", goldScope="POOL"))

    def test_fallback_mixed_environment_and_duplicate_rank_rejected(self):
        with self.assertRaises(ValueError): joined(observation("Q01", retrievalMode="LEXICAL_FALLBACK"))
        with self.assertRaises(ValueError): joined(observation("Q01"), observation("Q02", environment="other"))
        with self.assertRaises(ValueError): joined(observation("Q01", rankedRecipeIds=["358", "358"]))

    def test_old_scope_keeps_recall_and_new_scope_requires_targets(self):
        for scope in ("POOL", "COMPLETE"):
            self.assertIn("recallAt5", run(sample(goldScope=scope))["groups"][0]["retrieval"])
        with self.assertRaises(ValueError): run(sample(goldScope="SELECTED_TARGET", goldRecipeIds=[]))
        with self.assertRaises(ValueError): run(sample(goldScope="SELECTED_TARGET_EMPTY", goldRecipeIds=["1"]))
