"""T18离线观测汇总。只读显式JSON文件，不联网、不读环境变量、不执行产品动作。"""

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import sys

KINDS = {"retrieval", "meal", "guidance", "shopping", "calls"}
MODES = {"ISOLATED_SUBSTITUTE", "REAL_SUPPLIER", "LOCAL_MOCK", "USER_REVIEW", "DATABASE_READ_ONLY"}
OUTCOMES = {"SUCCEEDED", "CLARIFICATION", "NO_MATCH", "FAILED", "TIMEOUT", "UNKNOWN"}
SEARCH_MODES = {"HYBRID", "LEXICAL_FALLBACK", "VECTOR", "RANDOM", "NOT_APPLICABLE"}
FIELDS = {"id", "caseId", "kind", "mode", "path", "environment", "retrievalMode", "warmth", "outcome", "durationMs",
          "goldStatus", "goldScope", "goldEvidenceRef", "goldRecipeIds", "rankedRecipeIds", "options", "differentSets",
          "ratings", "ratingsSource", "recipeIds", "lines", "methodAttempts", "httpAttempts", "inputTokens", "outputTokens",
          "unauthorizedLeaks", "replayCallDelta", "factMutations"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value, integer=False):
    require(value is None or (type(value) in (int, float) and math.isfinite(value) and value >= 0
                             and (not integer or type(value) is int)), "数值须为有限非负值，计数须为整数；未知用null")


def booleans(value):
    require(value is None or type(value) is bool, "判断须为布尔值或null，不能自动转换")


def ids(value):
    require(value is None or (isinstance(value, list)
            and all(isinstance(x, str) and x and x.isascii() and x.isdecimal() and x[0] != "0" for x in value)
            and len(value) == len(set(value))),
            "菜谱ID须为不重复的正整数字符串，未知集合用null")


def validate(row):
    require(isinstance(row, dict) and set(row) <= FIELDS, "观测存在未知字段；不接收正文、Prompt或凭证")
    for key in ("id", "path", "environment"):
        require(isinstance(row.get(key), str) and 0 < len(row[key]) <= 200, "观测ID、路径和环境须明确且有界")
    require(row.get("kind") in KINDS and row.get("mode") in MODES and row.get("outcome") in OUTCOMES,
            "必须明确指标层、证据模式和结果状态")
    require(row.get("retrievalMode") in SEARCH_MODES and row.get("warmth") in {"COLD", "WARM", "UNKNOWN"},
            "必须明确检索模式和冷热状态")
    for key in ("durationMs", "methodAttempts", "httpAttempts", "inputTokens", "outputTokens",
                "unauthorizedLeaks", "replayCallDelta", "factMutations"):
        number(row.get(key), integer=key != "durationMs")
    if row["kind"] == "retrieval":
        require(row.get("goldStatus") in {"PENDING", "REVIEWED"}, "gold审定状态必须明确")
        ids(row.get("goldRecipeIds")); ids(row.get("rankedRecipeIds"))
        if row["goldStatus"] == "REVIEWED":
            require(row.get("goldScope") in {"COMPLETE", "POOL", "SELECTED_TARGET", "SELECTED_TARGET_EMPTY"} and row.get("goldRecipeIds") is not None
                    and isinstance(row.get("goldEvidenceRef"), str) and bool(row["goldEvidenceRef"].strip()),
                    "审定gold须有完整/池内范围、明确集合及新快照人工依据")
            if row.get("goldScope") == "SELECTED_TARGET":
                require(bool(row["goldRecipeIds"]), "示例目标题须有明确目标；空结果单列")
            if row.get("goldScope") == "SELECTED_TARGET_EMPTY":
                require(row["goldRecipeIds"] == [], "空结果题不能填目标")
        else:
            require(row.get("goldRecipeIds") is None, "待审gold禁止预填ID")
        if row["outcome"] in {"SUCCEEDED", "NO_MATCH"}:
            require(row.get("rankedRecipeIds") is not None, "已完成检索须明确实际返回集合")
        if row["outcome"] in {"FAILED", "TIMEOUT", "UNKNOWN"}:
            require(not row.get("rankedRecipeIds"), "失败或未知检索不能提供成功排名")
    if row["kind"] == "meal":
        require(isinstance(row.get("options"), list), "餐单须列明实际方案数组，未产出用空数组")
        for option in row["options"]:
            require(isinstance(option, dict) and set(option) == {"roleQuantitySatisfied", "deduplicated", "hardViolations"},
                    "方案须明确角色数量、去重和硬条件核验")
            booleans(option["roleQuantitySatisfied"]); booleans(option["deduplicated"]); number(option["hardViolations"], True)
        booleans(row.get("differentSets"))
    if row["kind"] == "guidance":
        ratings = row.get("ratings", {})
        require(isinstance(ratings, dict) and set(ratings) <= {"faithfulness", "executability", "supplementClarity", "tone"},
                "指导评分字段无效")
        for value in ratings.values():
            require(value is None or (type(value) is int and 1 <= value <= 5), "人工评分须为1～5整数或null")
        ids(row.get("recipeIds"))
        require(not any(v is not None for v in ratings.values()) or
                (row.get("ratingsSource") == "USER" and bool(row.get("recipeIds"))),
                "人工评分须关联所选菜谱，不能用自动引用存在检查冒充用户评分")
    if row["kind"] == "shopping":
        require(isinstance(row.get("lines"), list), "采购须列明确待购买项，足量已有不混入此分母")
        for line in row["lines"]:
            core = {"quantityCalculable", "matchEligible", "matched", "costCalculable"}
            require(isinstance(line, dict) and core <= set(line) <= core | {"reasonCode"},
                    "采购项须保留量、匹配资格、结果和成本可计算性")
            for field in core:
                booleans(line[field])
            require(line.get("reasonCode", "UNKNOWN") in {"NONE", "UNKNOWN", "QUANTITY_PENDING", "UNREVIEWED_ID",
                    "UNIT_UNSUPPORTED", "NO_PRODUCT", "OUT_OF_STOCK", "QUOTE_UNKNOWN"}, "采购原因须为有限安全类别")
            require(line["matched"] is not True or line["matchEligible"] is True, "无明确标准ID/数量/单位资格不能报告商品匹配")


def fraction(values):
    return {"numerator": sum(v is True for v in values), "denominator": len(values),
            "unknown": sum(v is None for v in values),
            "rate": sum(v is True for v in values) / len(values) if values and None not in values else None}


def counted(values):
    return {"knownSubtotal": sum(v for v in values if v is not None), "unknownRows": sum(v is None for v in values),
            "total": sum(values) if values and None not in values else None}


def latency(rows):
    success = [r["durationMs"] for r in rows if r["outcome"] == "SUCCEEDED" and r.get("durationMs") is not None]
    failed = [r["durationMs"] for r in rows if r["outcome"] in {"FAILED", "TIMEOUT"} and r.get("durationMs") is not None]
    ordered = sorted(success)
    return {"algorithm": "nearest-rank ceil(0.95*n)，同组至少30个成功且计时已知样本",
            "successTimedN": len(success), "successDurationUnknownN": sum(r["outcome"] == "SUCCEEDED" and r.get("durationMs") is None for r in rows),
            "successfulTimesMs": success, "failedOrTimedOutTimesMs": failed,
            "minMs": min(success) if success else None, "maxMs": max(success) if success else None,
            "p95Ms": ordered[math.ceil(.95 * len(ordered)) - 1] if len(ordered) >= 30 else None,
            "p95Status": "MEASURED" if len(ordered) >= 30 else "INSUFFICIENT_SUCCESS_SAMPLES"}


def retrieval(rows):
    reviewed = [r for r in rows if r["goldStatus"] == "REVIEWED"]
    positive = [r for r in reviewed if r["goldRecipeIds"]]
    unknown = sum(r["outcome"] == "UNKNOWN" for r in positive)
    result = {"totalQueries": len(rows), "pendingGoldQueries": len(rows) - len(reviewed),
              "positiveGoldDenominator": len(positive), "unknownResultQueries": unknown,
              "mrrRankScope": "输入的实际排名列表，非无限全库排名", "mrr": None}
    reciprocal = []
    for row in positive:
        ranks = row.get("rankedRecipeIds") or []
        reciprocal.append(next((1 / (i + 1) for i, value in enumerate(ranks) if value in row["goldRecipeIds"]), 0))
    if positive and not unknown:
        result["mrr"] = sum(reciprocal) / len(positive)
    for k in (5, 10):
        hit = sum(bool(set((r.get("rankedRecipeIds") or [])[:k]) & set(r["goldRecipeIds"])) for r in positive)
        recalls = [len(set((r.get("rankedRecipeIds") or [])[:k]) & set(r["goldRecipeIds"])) / len(r["goldRecipeIds"]) for r in positive]
        result[f"hitAt{k}"] = {"numerator": hit, "denominator": len(positive), "unknown": unknown,
                                "rate": hit / len(positive) if positive and not unknown else None}
        if rows[0].get("goldScope") not in {"SELECTED_TARGET", "SELECTED_TARGET_EMPTY"}:
            result[f"recallAt{k}"] = {"denominator": len(positive), "scope": rows[0].get("goldScope"),
                                       "macroMean": sum(recalls) / len(positive) if positive and not unknown else None}
    if rows[0].get("goldScope") in {"SELECTED_TARGET", "SELECTED_TARGET_EMPTY"}:
        result["metricMeaning"] = "人工示例目标命中及截断列表首目标MRR；未标注结果相关性未知"
    empty = [r for r in reviewed if r["goldRecipeIds"] == []]
    result["reviewedNoResult"] = fraction([None if r["outcome"] == "UNKNOWN" else
        r["outcome"] in {"SUCCEEDED", "NO_MATCH"} and r["rankedRecipeIds"] == [] for r in empty])
    return result


def summarize(document):
    require(isinstance(document, dict) and set(document) <= {"schemaVersion", "samples", "note"}
            and document.get("schemaVersion") == 1 and isinstance(document.get("samples"), list), "观测文件结构或版本无效")
    rows = document["samples"]
    require(len(rows) <= 10000, "一次离线汇总最多10000条观测")
    groups = defaultdict(list)
    seen = set()
    for row in rows:
        validate(row)
        require(row["id"] not in seen, "观测ID重复，禁止重复计入分母")
        seen.add(row["id"])
        # gold完整性、模式、路径、环境、冷热均隔离，不能用替身或冷启动补真实暖样本。
        key = tuple(row[field] for field in ("kind", "mode", "path", "environment", "retrievalMode", "warmth")) + (row.get("goldScope"),)
        groups[key].append(row)
    output = []
    for key, samples in sorted(groups.items(), key=lambda item: str(item[0])):
        group = dict(zip(("kind", "mode", "path", "environment", "retrievalMode", "warmth", "goldScope"), key))
        counts = Counter(row["outcome"] for row in samples)
        group.update({"sampleIds": [r["id"] for r in samples], "requestDenominator": len(samples), "outcomes": dict(counts),
                      "outcomeRates": {state: {"numerator": counts[state], "denominator": len(samples),
                                        "rate": counts[state] / len(samples)} for state in sorted(OUTCOMES)},
                      "observedSuccessRate": counts["SUCCEEDED"] / len(samples), "unknownOutcomeN": counts["UNKNOWN"],
                      "latency": latency(samples)})
        group["safety"] = {field: counted([r.get(field) for r in samples]) for field in
                           ("unauthorizedLeaks", "replayCallDelta", "factMutations")}
        if key[0] == "retrieval":
            group["retrieval"] = retrieval(samples)
        elif key[0] == "meal":
            options = [o for r in samples for o in r["options"]]
            group["meal"] = {"optionDenominator": len(options),
                "roleQuantitySatisfied": fraction([o["roleQuantitySatisfied"] for o in options]),
                "deduplicated": fraction([o["deduplicated"] for o in options]),
                "hardViolations": counted([o["hardViolations"] for o in options]),
                "twoOptionDifference": fraction([r.get("differentSets") for r in samples if len(r["options"]) == 2])}
        elif key[0] == "guidance":
            group["ratingReferences"] = [{"sampleId": r["id"], "recipeIds": r.get("recipeIds")}
                                         for r in samples if r.get("ratingsSource") == "USER"]
            group["humanRatings"] = {}
            for field in ("faithfulness", "executability", "supplementClarity", "tone"):
                values = [r.get("ratings", {}).get(field) for r in samples]
                known = [v for v in values if v is not None]
                group["humanRatings"][field] = {"ratedN": len(known), "unknownN": len(values) - len(known),
                                                  "mean": sum(known) / len(known) if known else None}
        elif key[0] == "shopping":
            lines = [line for r in samples for line in r["lines"]]
            eligible = [line for line in lines if line["matchEligible"] is True]
            group["shopping"] = {"purchaseNeedDenominator": len(lines),
                "reasonCounts": dict(Counter(line.get("reasonCode", "UNKNOWN") for line in lines)),
                "quantityCalculable": fraction([line["quantityCalculable"] for line in lines]),
                "overallMatchCoverage": fraction([line["matched"] for line in lines]),
                "eligibleMatchCoverage": fraction([line["matched"] for line in eligible]),
                "unknownEligibilityN": sum(line["matchEligible"] is None for line in lines),
                "costCalculable": fraction([line["costCalculable"] for line in lines]),
                "consumptionCost": None, "purchaseOutlay": None, "amountStatus": "金额另取已存快照，不把比例或已知小计当完整金额"}
        elif key[0] == "calls":
            group["calls"] = {field: counted([r.get(field) for r in samples]) for field in
                              ("methodAttempts", "httpAttempts", "inputTokens", "outputTokens")}
            group["calls"]["unknownUsageRate"] = sum(r.get("inputTokens") is None or r.get("outputTokens") is None for r in samples) / len(samples)
            group["calls"]["paidAmount"] = None
        output.append(group)
    return {"schemaVersion": 1, "sampleN": len(rows), "measurementStatus": "NO_SAMPLES" if not rows else "OBSERVATIONS_ONLY",
            "groups": output, "note": "离线汇总不证明输入标签正确、真实调用发生或账单结算；0样本不产生质量和p95数字"}


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, "JSON字段重复，拒绝汇总")
        value[key] = item
    return value


def main(argv=None):
    parser = argparse.ArgumentParser(description="T18离线观测汇总，不联网或执行业务")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)
    try:
        source, target = Path(args.input), Path(args.output)
        require(source.stat().st_size <= 5_000_000, "观测文件超过5MB上限")
        document = json.loads(source.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object,
                              parse_constant=lambda _: (_ for _ in ()).throw(ValueError("JSON禁止NaN或Infinity")))
        result = summarize(document)
        # 只创建新结果，不能覆盖原始观测或旧统计证据。
        with target.open("x", encoding="utf-8") as handle:
            json.dump(result, handle, ensure_ascii=False, indent=2, allow_nan=False)
            handle.write("\n")
        print("离线汇总完成，样本数：" + str(result["sampleN"]))
        return 0
    except (ValueError, OSError, TypeError):
        print("汇总拒绝：检查格式、分母字段和文件路径；不得覆盖旧结果，错误不回显观测正文", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
