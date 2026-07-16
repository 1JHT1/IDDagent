#!/usr/bin/env python3.13
# -*- coding: utf-8 -*-
"""
企业名称模糊匹配场景测试
验证修复后的匹配质量阈值逻辑
"""
import asyncio
import sys
from pathlib import Path

# 确保可以导入 skills 模块
sys.path.insert(0, str(Path(__file__).parent.parent))

from skills.risk_check import _fuzzy_match_company, _load_json, _resolve_company_match, NAME_INDEX_FILE

name_index = _load_json(NAME_INDEX_FILE)


def test_fuzzy(query: str, expected_action: str, expected_name: str | None = None):
    """测试模糊匹配 + resolve 结果"""
    resolved = _resolve_company_match(query, name_index)
    action = resolved.get("action", "auto_match" if "credit_code" in resolved else "unknown")

    # 判断 pass/fail
    passed = action == expected_action
    if expected_name and "credit_code" in resolved:
        # 需要验证匹配到的企业名称
        code = resolved["credit_code"]
        actual_name = name_index.get(code, "")
        if actual_name != expected_name:
            passed = False

    status = "✅ PASS" if passed else "❌ FAIL"
    print(f"  {status}  「{query}」")
    print(f"         action: {action}", end="")
    if "credit_code" in resolved:
        print(f"  → {name_index.get(resolved['credit_code'], '?')}")
    elif "options" in resolved:
        names = [o["company_name"] for o in resolved["options"]]
        print(f"  → suggestions: {names}")
    else:
        print(f"  msg: {resolved.get('message', '')}")

    if not passed:
        print(f"         ⚠️  期望: {expected_action}" + (f" → {expected_name}" if expected_name else ""))
    print()
    return passed


def main():
    print("=" * 70)
    print("企业名称模糊匹配场景测试")
    print("=" * 70)

    results = []

    # ── 场景1: 去后缀精确匹配 → 应自动选中 ──
    print("\n📌 场景1: 去后缀精确匹配（应自动选中）")
    results.append(test_fuzzy("红米子集科技", "auto_match", "红米子集科技有限公司"))
    results.append(test_fuzzy("北京星河科技", "auto_match", "北京星河科技有限公司"))
    results.append(test_fuzzy("木棉花供应链管理", "auto_match", "木棉花供应链管理有限公司"))
    results.append(test_fuzzy("叶子科技", "auto_match", "叶子科技有限公司"))
    results.append(test_fuzzy("小米至善科技", "auto_match", "小米至善科技有限公司"))

    # ── 场景2: 完整名称精确匹配 → 应自动选中 ──
    print("\n📌 场景2: 完整名称精确匹配（应自动选中）")
    results.append(test_fuzzy("北京星河科技有限公司", "auto_match", "北京星河科技有限公司"))
    results.append(test_fuzzy("深圳前海创新金融集团有限公司", "auto_match", "深圳前海创新金融集团有限公司"))

    # ── 场景3: 过度缩写 → 应返回 not_found + 建议 ──
    print("\n📌 场景3: 过度缩写（应返回 not_found + 相似建议）")
    results.append(test_fuzzy("红米科技", "not_found"))
    results.append(test_fuzzy("小米科技", "auto_match", "小米科技有限公司"))  # 精确匹配(score=95)，次优(72)<80，自动选中
    results.append(test_fuzzy("星河科技", "not_found"))

    # ── 场景4: 完全不存在 → 应返回 not_found（无建议） ──
    print("\n📌 场景4: 完全不存在（应返回 not_found）")
    results.append(test_fuzzy("张三食品有限公司", "not_found"))
    results.append(test_fuzzy("完全不存在的公司", "not_found"))

    # ── 场景5: 多关键词匹配 → 应自动选中 ──
    print("\n📌 场景5: 多关键词匹配（应自动选中）")
    results.append(test_fuzzy("北京 星河", "auto_match", "北京星河科技有限公司"))
    results.append(test_fuzzy("重庆 供应链", "auto_match", "重庆两江新区供应链管理有限公司"))

    # ── 场景6: 小米食品相关（新增数据验证） ──
    print("\n📌 场景6: 小米食品相关（新增数据验证）")
    results.append(test_fuzzy("小米食品", "auto_match", "小米食品有限公司"))
    results.append(test_fuzzy("小米食品有限公司", "auto_match", "小米食品有限公司"))

    # ── 场景6: 评分验证 ──
    print("\n📌 场景6: 评分明细验证")
    test_cases = [
        ("红米子集科技", 95, "去后缀精确匹配"),
        ("红米科技", None, "子序列匹配（低分）"),
        ("北京星河科技有限公司", 95, "完整名称去后缀精确匹配"),
    ]
    for query, expected_score, desc in test_cases:
        matches = _fuzzy_match_company(query, name_index)
        if matches:
            actual_score = matches[0]["_score"]
            status = "✅" if (expected_score is None or actual_score == expected_score) else "❌"
            print(f"  {status} 「{query}」({desc}): score={actual_score}" +
                  (f" (期望={expected_score})" if expected_score else ""))
            results.append(expected_score is None or actual_score == expected_score)
        else:
            print(f"  ❌ 「{query}」({desc}): 无匹配")
            results.append(False)
        print()

    # ── 汇总 ──
    total = len(results)
    passed = sum(results)
    print("=" * 70)
    print(f"测试结果: {passed}/{total} 通过" + (" ✅ 全部通过！" if passed == total else " ❌ 有失败项"))
    print("=" * 70)

    return passed == total


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
