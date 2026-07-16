# -*- coding: utf-8 -*-
"""
拓户准备技能插件
当用户询问营销材料准备时，Coordinator 识别意图后调用此技能
根据统一信用代码返回客户触达信息、营销谈资H5、营销话术H5
"""
import json
from pathlib import Path
from typing import Any

from . import Skill, skill_registry
from .risk_check import _load_json, _build_base_url, _fuzzy_match_company, _resolve_company_match

DATA_DIR = Path(__file__).parent.parent / "data"
OUTREACH_FILE = DATA_DIR / "customer_outreach.json"
NAME_INDEX_FILE = DATA_DIR / "company_name_index.json"


async def handle_customer_outreach(user_id: str, params: dict) -> dict:
    """
    拓户准备处理

    params:
        credit_code (可选): 统一信用代码，直接查询
        company_name (可选): 企业名称，通过模糊匹配查找
    """
    credit_code = params.get("credit_code", "").strip()
    company_name = params.get("company_name", "").strip()

    outreach_data = _load_json(OUTREACH_FILE)
    base_url = _build_base_url()

    # ── 通过统一信用代码查询 ──
    if credit_code:
        result = outreach_data.get(credit_code)
        if result:
            return _build_response(result, base_url)
        return {
            "action": "not_found",
            "message": f"未查询到统一信用代码为 {credit_code} 的企业的拓户准备资料，请核实代码是否正确。",
        }

    # ── 通过企业名称查询 ──
    if company_name:
        name_index = _load_json(NAME_INDEX_FILE)
        resolved = _resolve_company_match(company_name, name_index)

        # 高置信度单匹配
        if "credit_code" in resolved:
            code = resolved["credit_code"]
            result = outreach_data.get(code)
            if result:
                return _build_response(result, base_url)
            return {
                "action": "not_found",
                "message": f"已定位到企业，但暂无该企业的拓户准备资料。",
            }

        return resolved

    return {
        "action": "not_found",
        "message": "请提供企业名称或统一信用代码进行拓户准备资料查询。",
    }


def _build_response(data: dict, base_url: str) -> dict:
    """构建标准响应结构"""
    code = data["credit_code"]
    channels = data.get("contact_channels", [])
    return {
        "action": "result",
        "credit_code": code,
        "company_name": data["company_name"],
        "business_address": data.get("business_address", ""),
        "registered_address": data.get("registered_address", ""),
        "contact_channels": channels,
        "insights_h5_url": f"{base_url}/h5/marketing-insights.html?code={code}",
        "script_h5_url": f"{base_url}/h5/marketing-script.html?code={code}",
        "channel_count": len(channels),
    }


# ============================================================
# 注册技能
# ============================================================
skill_registry.register(
    Skill(
        name="prepare_customer_outreach",
        description=(
            "当用户询问「准备拓户材料」「准备营销材料」「准备客户触达信息」「营销谈资」「营销话术」"
            "「拓户准备」「准备对xx客户进行营销」时调用此技能。"
            "根据企业名称或统一信用代码返回客户触达渠道、营销谈资和营销话术链接。"
        ),
        handler=handle_customer_outreach,
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
