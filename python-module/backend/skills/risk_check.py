# -*- coding: utf-8 -*-
"""
风险预查技能插件
当用户询问企业开户风险时，Coordinator 识别意图后调用此技能
支持按统一信用代码查询，也支持按企业名称模糊匹配
"""
import json
from pathlib import Path
from typing import Any

from . import Skill, skill_registry

# 数据文件路径
DATA_DIR = Path(__file__).parent.parent / "data"
RISK_FILE = DATA_DIR / "risk_check.json"
NAME_INDEX_FILE = DATA_DIR / "company_name_index.json"


# 自动匹配的最低得分阈值（低于此分数不自动选中，返回建议列表）
MIN_AUTO_MATCH_SCORE = 80
# 最多展示的相似企业数量
MAX_SUGGESTIONS = 3


def _load_json(filepath: Path) -> dict:
    if not filepath.exists():
        return {}
    with open(filepath, "r", encoding="utf-8") as f:
        return json.load(f)


def _build_base_url() -> str:
    import os
    host = os.getenv("HOST", "localhost")
    port = os.getenv("PORT", "8000")
    return f"http://{host}:{port}"


def _is_subsequence(query: str, target: str) -> bool:
    """检查 query 的每个字符是否按顺序出现在 target 中（子序列匹配）"""
    qi = 0
    for ch in target:
        if qi < len(query) and ch == query[qi]:
            qi += 1
    return qi == len(query)


def _fuzzy_match_company(query: str, name_index: dict) -> list[dict]:
    """
    智能模糊匹配企业名称，支持：
    1. 精确匹配
    2. 多关键词AND匹配（空格分隔）
    3. 字符级子序列匹配（如"重庆供应链"→"重庆两江新区供应链管理有限公司"）
    4. 简单子串包含匹配（回退方案）
    
    返回匹配列表，按匹配质量排序，name.index(query) 越小质量越高
    """
    if not query:
        return []

    # 1. 精确匹配
    if query in name_index:
        return [{"credit_code": name_index[query], "company_name": query, "_score": 100}]

    results: dict[str, dict] = {}  # code -> match entry

    # 2. 多关键词匹配（空格/全角空格分隔，AND 逻辑）
    normalized = query.replace('\u3000', ' ').replace('  ', ' ').strip()
    if ' ' in normalized:
        keywords = normalized.split()
        for code, name in name_index.items():
            if all(kw in name for kw in keywords):
                results[code] = {"credit_code": code, "company_name": name, "_score": 80}
        if results:
            return sorted(results.values(), key=lambda x: x["_score"], reverse=True)

    # 3. 字符级子序列匹配（每个字符按顺序出现在目标中）
    for code, name in name_index.items():
        # 去掉用户输入和目标中的常见后缀/前缀再比较，提高匹配率
        clean_query = query.replace('有限公司', '').replace('有限责任', '').replace('股份', '').replace('集团', '').replace('公司', '')
        clean_name = name.replace('有限公司', '').replace('有限责任', '').replace('股份', '').replace('集团', '').replace('公司', '')
        
        if _is_subsequence(clean_query, clean_name):
            if clean_query == clean_name:
                # 去后缀后完全一致，视为高质量匹配
                score = 95
            else:
                # 评分：匹配密度越高分数越高
                density = len(query) / len(name)
                score = 60 + int(density * 30)
            results[code] = {"credit_code": code, "company_name": name, "_score": score}

    # 4. 简单子串包含匹配
    if not results:
        for code, name in name_index.items():
            if query in name:
                results[code] = {"credit_code": code, "company_name": name, "_score": 40}

    return sorted(results.values(), key=lambda x: x["_score"], reverse=True)


def _resolve_company_match(company_name: str, name_index: dict) -> dict:
    """
    解析企业名称匹配结果，返回标准化响应。

    返回类型：
    - {"credit_code": "..."} — 高置信度单匹配，可自动选择
    - {"action": "not_found", ...} — 无匹配或低质量匹配，附相似企业建议
    - {"action": "ambiguous", ...} — 多个高质匹配需用户选择
    """
    matches = _fuzzy_match_company(company_name, name_index)

    if not matches:
        return {
            "action": "not_found",
            "message": f"未找到与「{company_name}」匹配的企业，请确认企业名称是否正确。可尝试使用更简短的关键词，或提供统一信用代码查询。",
        }

    options = [
        {"credit_code": m["credit_code"], "company_name": m["company_name"]}
        for m in matches[:MAX_SUGGESTIONS]
    ]

    # 单匹配
    if len(matches) == 1:
        if matches[0]["_score"] >= MIN_AUTO_MATCH_SCORE:
            return {"credit_code": matches[0]["credit_code"]}
        # 低质量匹配：返回未找到 + 建议
        return {
            "action": "not_found",
            "keyword": company_name,
            "options": options,
            "message": f"未找到与「{company_name}」完全匹配的企业，您是否要查询以下相似企业？",
        }

    # 多匹配
    best_score = matches[0]["_score"]
    second_score = matches[1]["_score"] if len(matches) > 1 else 0

    # 最佳匹配明确领先（去后缀精确匹配 + 次优低于阈值）→ 自动选中
    if best_score >= 95 and second_score < MIN_AUTO_MATCH_SCORE:
        return {"credit_code": matches[0]["credit_code"]}

    # 多个高质匹配 → 让用户选择
    if best_score >= MIN_AUTO_MATCH_SCORE:
        return {
            "action": "ambiguous",
            "keyword": company_name,
            "options": options,
            "message": f"搜索到 {len(matches)} 家与「{company_name}」匹配的企业，请确认要查询哪一家：",
        }

    # 所有匹配质量都低：返回未找到 + 建议
    return {
        "action": "not_found",
        "keyword": company_name,
        "options": options,
        "message": f"未找到与「{company_name}」完全匹配的企业，以下是名称相似的企业：",
    }


async def handle_risk_check(user_id: str, params: dict) -> dict:
    """
    风险预查处理

    params:
        credit_code (可选): 统一信用代码，直接查询
        company_name (可选): 企业名称，通过名称索引查找
    """
    credit_code = params.get("credit_code", "").strip()
    company_name = params.get("company_name", "").strip()

    risk_data = _load_json(RISK_FILE)

    # ── 情况1：通过统一信用代码查询 ──
    if credit_code:
        result = risk_data.get(credit_code)
        if result:
            base_url = _build_base_url()
            return {
                "action": "result",
                "credit_code": result["credit_code"],
                "company_name": result["company_name"],
                "has_risk": result["has_risk"],
                "risk_level": result["risk_level"],
                "risk_summary": result["risk_summary"],
                "h5_url": f"{base_url}/h5/risk-report.html?code={credit_code}"
            }
        return {
            "action": "not_found",
            "message": f"未查询到统一信用代码为 {credit_code} 的企业风险信息，请核实代码是否正确。",
        }

    # ── 情况2：通过企业名称查询 ──
    if company_name:
        name_index = _load_json(NAME_INDEX_FILE)
        resolved = _resolve_company_match(company_name, name_index)

        # 高置信度单匹配：自动选择
        if "credit_code" in resolved:
            return await handle_risk_check(
                user_id, {"credit_code": resolved["credit_code"]}
            )

        return resolved

    return {
        "action": "not_found",
        "message": "请提供企业名称或统一信用代码进行查询。",
    }


# 注册技能
skill_registry.register(
    Skill(
        name="check_company_risk",
        description=(
            "当用户询问查询企业开户风险、风险预查、企业风险筛查、"
            "查询xx客户是否存在开户风险、风险预检时调用此技能。"
            "根据企业统一信用代码或企业名称查询风险信息，"
            "返回风险结论和详细风险报告链接。"
        ),
        handler=handle_risk_check,
        parameters={
            "credit_code": {
                "type": "string",
                "description": "企业统一信用代码，18位数字+字母",
                "required": False,
                "example": "91110108MA01B3XK2P",
            },
            "company_name": {
                "type": "string",
                "description": "企业名称，用于模糊匹配",
                "required": False,
                "example": "北京星河科技有限公司",
            },
        },
    )
)
