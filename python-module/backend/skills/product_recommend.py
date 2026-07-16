# -*- coding: utf-8 -*-
"""
产品智荐技能插件
当用户询问为客户推荐产品时，Coordinator 识别意图后调用此技能
根据统一信用代码返回推荐产品列表（按优先级排序）+ H5详情链接
"""
import json
from pathlib import Path
from typing import Any

from . import Skill, skill_registry
from .risk_check import _load_json, _build_base_url, _fuzzy_match_company, _resolve_company_match

DATA_DIR = Path(__file__).parent.parent / "data"
PRODUCT_FILE = DATA_DIR / "product_recommendations.json"
NAME_INDEX_FILE = DATA_DIR / "company_name_index.json"

PRIORITY_ORDER = {"high": 0, "medium": 1, "low": 2}
PRIORITY_LABEL = {"high": "高优先级", "medium": "中优先级", "low": "低优先级"}


async def handle_product_recommend(user_id: str, params: dict) -> dict:
    """
    产品智荐处理

    params:
        credit_code (可选): 统一信用代码
        company_name (可选): 企业名称
    """
    credit_code = params.get("credit_code", "").strip()
    company_name = params.get("company_name", "").strip()

    all_data = _load_json(PRODUCT_FILE)
    recommendations = all_data.get("recommendations", {})
    product_pool = all_data.get("products", {})
    base_url = _build_base_url()

    def build_response(rec_data: dict) -> dict:
        code = rec_data["credit_code"]
        recs = sorted(rec_data["recommendations"], key=lambda r: PRIORITY_ORDER.get(r["priority"], 99))
        products = []
        for r in recs:
            prod = product_pool.get(r["key"], {})
            products.append({
                "product_name": prod.get("product_name", r["key"]),
                "category": prod.get("category", ""),
                "priority": r["priority"],
                "priority_label": PRIORITY_LABEL.get(r["priority"], ""),
                "reason": r["reason"],
                "expected_amount": r.get("expected_amount", ""),
                "features": prod.get("features", []),
                "application_period": prod.get("application_period", ""),
            })
        return {
            "action": "result",
            "credit_code": code,
            "company_name": rec_data["company_name"],
            "analysis_summary": rec_data.get("analysis_summary", ""),
            "products": products,
            "detail_h5_url": f"{base_url}/h5/product-recommend.html?code={code}",
            "total_count": len(products),
        }

    # ── 通过统一信用代码查询 ──
    if credit_code:
        result = recommendations.get(credit_code)
        if result:
            return build_response(result)
        return {
            "action": "not_found",
            "message": f"未查询到信用代码 {credit_code} 的企业的产品推荐信息。",
        }

    # ── 通过企业名称查询 ──
    if company_name:
        name_index = _load_json(NAME_INDEX_FILE)
        resolved = _resolve_company_match(company_name, name_index)

        # 高置信度单匹配
        if "credit_code" in resolved:
            code = resolved["credit_code"]
            result = recommendations.get(code)
            if result:
                return build_response(result)
            return {
                "action": "not_found",
                "message": f"已定位到企业，但暂无该企业的产品推荐。",
            }

        return resolved

    return {
        "action": "not_found",
        "message": "请提供企业名称或统一信用代码进行产品推荐查询。",
    }


skill_registry.register(
    Skill(
        name="recommend_products",
        description=(
            "当用户询问「推荐产品」「为客户推荐产品」「产品推荐」「产品智荐」"
            "「适合什么产品」「产品匹配」时调用此技能。"
            "根据企业信用代码或名称，返回匹配的金融产品推荐列表（按优先级排序）及详细分析H5链接。"
        ),
        handler=handle_product_recommend,
        parameters={
            "credit_code": {
                "type": "string",
                "description": "企业统一信用代码",
                "required": False,
                "example": "91110108MA01B3XK2P",
            },
            "company_name": {
                "type": "string",
                "description": "企业名称",
                "required": False,
                "example": "北京星河科技有限公司",
            },
        },
    )
)
