"""只读冻结人工目标，关联独立观测。缺题留未知，禁止用排名改目标。"""
import argparse
import copy
import json
from pathlib import Path
import sys
from summarize import require, ids, summarize, unique_object

POSITIVE = [f"Q{i:02}" for i in range(1, 18)]
SCORED = POSITIVE + ["Q19"]


def load(path):
    require(Path(path).stat().st_size <= 5_000_000, "输入超过5MB")
    return json.loads(Path(path).read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("禁止非有限数")))


def validate_targets(targets):
    require(targets.get("schemaVersion") == 1 and targets.get("datasetId") == "t18-selected-targets-v1"
            and targets.get("annotationStatus") == "FROZEN_PARTIAL_TARGETS" and targets.get("approvedBy") == "USER",
            "仅接收已冻结用户示例目标集")
    queries = targets.get("queries")
    require(isinstance(queries, list) and [q.get("id") for q in queries] == [f"Q{i:02}" for i in range(1, 21)],
            "固定20题须唯一完整且顺序一致")
    policy = targets.get("metricPolicy", {})
    require(policy.get("positiveQueryDenominator") == 17 and policy.get("emptyResultQueryDenominator") == 1
            and policy.get("excludedQueryCount") == 2 and policy.get("totalQueryCount") == 20, "分母不允许漂移")
    for q in queries:
        require(isinstance(q.get("query"), str) and q["query"].strip() and isinstance(q.get("conditions"), dict), "问题及条件必填")
        target_ids = q.get("targetRecipeIds")
        ids(target_ids)
        expected = "SELECTED_TARGET" if q["id"] in POSITIVE else "EMPTY_RESULT" if q["id"] == "Q19" else "EXCLUDED"
        require(q.get("scoringMode") == expected, "题目计分模式不允许漂移")
        if expected == "SELECTED_TARGET":
            require(bool(target_ids), "正例目标不能为空")
            refs = q.get("targetReferences", [])
            require([r.get("recipeId") for r in refs] == target_ids and all(
                r.get("sourceType") == "SYSTEM_HOWTOCOOK" and r.get("sourceKey") and r.get("sourceVersion") and r.get("name")
                for r in refs), "目标须关联源身份")
        else:
            require(target_ids == ([] if expected == "EMPTY_RESULT" else None), "排除题未知不得变负例")
    require(sum(len(q["targetRecipeIds"]) for q in queries[:17]) == 20, "20个目标ID数量漂移")
    return {q["id"]: q for q in queries}


def join_and_summarize(targets, observations):
    questions = validate_targets(targets)
    require(isinstance(observations, dict) and set(observations) <= {"schemaVersion", "samples", "note"}
            and observations.get("schemaVersion") == 1 and isinstance(observations.get("samples"), list), "观测结构无效")
    rows = observations["samples"]
    if not rows:
        return summarize(observations)
    # 同一完整批次只接收一种环境/路径/实际模式；降级须独立报告，不能静默混合。
    contexts = {(r.get("mode"), r.get("path"), r.get("environment"), r.get("warmth")) for r in rows}
    require(len(contexts) == 1, "同批次模式/路径/环境/冷热须一致，降级另报")
    seen = set()
    converted = []
    for original in rows:
        row = copy.deepcopy(original)
        case = row.get("caseId")
        require(case in SCORED and case not in seen, "仅17正例与Q19，一题至多一次")
        require(row.get("kind") == "retrieval" and not any(k.startswith("gold") for k in row), "观测不允许自行携带标签")
        require(row.get("retrievalMode") == "HYBRID" or (case == "Q19" and row.get("retrievalMode") == "NOT_APPLICABLE"
                and row.get("outcome") == "NO_MATCH" and row.get("methodAttempts") == 0 and row.get("httpAttempts") == 0),
                "仅Q19已证实零调用空资格可单列；词法降级不能当混合成功")
        seen.add(case)
        row.update(goldStatus="REVIEWED", goldScope="SELECTED_TARGET" if case in POSITIVE else "SELECTED_TARGET_EMPTY",
                   goldRecipeIds=questions[case]["targetRecipeIds"], goldEvidenceRef=targets["datasetId"])
        converted.append(row)
    mode, path, environment, warmth = next(iter(contexts))
    search_mode = "HYBRID"
    for case in SCORED:
        if case not in seen:
            converted.append(dict(id="missing-" + case, caseId=case, kind="retrieval", mode=mode, path=path,
                environment=environment, retrievalMode=search_mode, warmth=warmth, outcome="UNKNOWN", durationMs=None,
                rankedRecipeIds=None, goldStatus="REVIEWED", goldScope="SELECTED_TARGET" if case in POSITIVE else "SELECTED_TARGET_EMPTY",
                goldRecipeIds=questions[case]["targetRecipeIds"], goldEvidenceRef=targets["datasetId"]))
    result = summarize(dict(schemaVersion=1, samples=converted))
    result.update(observedSampleN=len(rows), plannedQueryN=18, positiveTargetDenominator=17,
                  emptyResultDenominator=1, excludedCaseIds=["Q18", "Q20"], missingCaseIds=[q for q in SCORED if q not in seen])
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="冻结示例目标离线关联，不执行检索")
    parser.add_argument("--targets", required=True)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)
    try:
        result = join_and_summarize(load(args.targets), load(args.input))
        with Path(args.output).open("x", encoding="utf-8") as handle:
            json.dump(result, handle, ensure_ascii=False, indent=2, allow_nan=False)
            handle.write("\n")
        print("示例目标离线关联完成；未执行供应商请求")
        return 0
    except (ValueError, OSError, TypeError, KeyError):
        print("关联拒绝：检查冻结集、唯一题目、独立观测与新输出路径", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
