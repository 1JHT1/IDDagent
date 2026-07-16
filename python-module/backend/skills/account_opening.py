# -*- coding: utf-8 -*-
"""
对公账户开户技能插件
当用户表示客户已同意办理开户时，Coordinator 识别意图后调用此技能。
创建开户申请，返回资料上传链接。
"""
import json
import os
import uuid
from datetime import datetime
from pathlib import Path
from typing import Optional

from . import Skill, skill_registry
from .risk_check import _load_json, _build_base_url, _resolve_company_match

DATA_DIR = Path(__file__).parent.parent / "data"
ACCOUNT_FILE = DATA_DIR / "account_opening.json"
NAME_INDEX_FILE = DATA_DIR / "company_name_index.json"
RISK_FILE = DATA_DIR / "risk_check.json"
OUTREACH_FILE = DATA_DIR / "customer_outreach.json"
PRODUCT_FILE = DATA_DIR / "product_recommendations.json"

# 上传文件存储目录
UPLOAD_DIR = Path(__file__).parent.parent / "static" / "uploads" / "account_opening"


def _load_account_data() -> dict:
    if ACCOUNT_FILE.exists():
        with open(ACCOUNT_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"applications": {}}


def _save_account_data(data: dict):
    ACCOUNT_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(ACCOUNT_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def _generate_account_number() -> str:
    """生成模拟对公账号"""
    import random
    prefix = "6225"
    suffix = "".join([str(random.randint(0, 9)) for _ in range(12)])
    return f"{prefix}{suffix}"


# ============================================================
# Mock OCR + 数据预填
# ============================================================

def _mock_ocr_and_prefill(credit_code: str, company_name: str) -> dict:
    """
    Mock OCR 处理 + 结合已有企业数据预填四大模块
    """
    # 加载企业已有信息
    risk_data = _load_json(RISK_FILE).get(credit_code, {})
    outreach_data = _load_json(OUTREACH_FILE).get(credit_code, {})
    prod_data = _load_json(PRODUCT_FILE).get("recommendations", {}).get(credit_code, {})

    # 企业名称 / 信用代码
    actual_name = risk_data.get("company_name") or outreach_data.get("company_name") or prod_data.get("company_name") or company_name

    # 注册地址
    address = outreach_data.get("business_address") or outreach_data.get("registered_address") or "（请核实后填写）"

    # 风险信息
    risk_level = risk_data.get("risk_level", "low")
    risk_summary = risk_data.get("risk_summary", "")

    # 行业分析
    analysis = prod_data.get("analysis_summary", "")

    # 模拟法人信息（Mock）
    import hashlib
    hash_seed = hashlib.md5(credit_code.encode()).hexdigest()[:8]
    mock_legal_rep = f"张{'明' if int(hash_seed[0], 16) % 2 == 0 else '伟'}"
    mock_rep_id = f"4403{hash_seed[:4]}{'1975' if int(hash_seed[4], 16) % 2 == 0 else '1980'}0{int(hash_seed[5], 16) % 9 + 1}15{hash_seed[6:8]}"
    mock_beneficiary = f"李{'华' if int(hash_seed[2], 16) % 2 == 0 else '强'}"

    form_data = {
        "company_info": {
            "company_name": actual_name,
            "credit_code": credit_code,
            "registered_address": address,
            "registered_capital": "（请核实后填写）",
            "business_scope": "（请核实后填写）",
            "legal_representative": mock_legal_rep,
            "legal_rep_id_number": mock_rep_id,
            "legal_rep_phone": f"138{hash_seed[:8]}",
            "beneficiary_name": mock_beneficiary,
            "beneficiary_id_number": f"4403{hash_seed[2:6]}1985{'0' + str(int(hash_seed[7], 16) % 9 + 1)}20{hash_seed[:2]}",
            "beneficiary_relationship": "实际控制人",
        },
        "account_info": {
            "account_type": "基本户",
            "currency": "人民币",
            "account_number": _generate_account_number(),
            "reserved_seal": "公章+法人章+财务章",
            "reconciliation_method": "电子对账（企业网银）",
        },
        "due_diligence": {
            "opening_purpose": "日常经营结算",
            "fund_source": "经营收入",
            "expected_transaction_volume": _estimate_volume(risk_data, analysis),
            "cross_border_involved": "否",
            "risk_rating": {"low": "低风险", "medium": "中风险", "high": "高风险"}.get(risk_level, "低风险"),
            "conclusion": f"经尽职调查，该企业{'经营状况正常，风险可控，建议受理' if risk_level != 'high' else '存在一定风险，建议加强尽调后审慎受理'}。",
        },
        "product_signing": {
            "enterprise_online_banking": True,
            "bank_enterprise_reconciliation": True,
            "enterprise_mobile_banking": True,
            "corporate_settlement_card": False,
            "payroll_service": False,
            "sms_notification": True,
        },
    }

    return form_data


def _estimate_volume(risk_data: dict, analysis: str) -> str:
    """根据企业画像估算交易规模"""
    if "亿" in analysis or "大" in analysis:
        return "年交易规模5000万-1亿"
    elif "中型" in analysis or "稳定" in analysis:
        return "年交易规模1000万-5000万"
    else:
        return "年交易规模500万-1000万"


# ============================================================
# Skill Handler
# ============================================================

async def handle_account_opening(user_id: str, params: dict) -> dict:
    """
    对公账户开户处理

    params:
        company_name (可选): 企业名称
        credit_code (可选): 统一信用代码
    """
    company_name = params.get("company_name", "").strip()
    credit_code = params.get("credit_code", "").strip()
    conversation_id = params.get("_conversation_id", "")
    base_url = _build_base_url()

    # 名称索引（credit_code -> company_name 映射）
    name_index = _load_json(NAME_INDEX_FILE)

    # 解析企业名称
    if company_name and not credit_code:
        resolved = _resolve_company_match(company_name, name_index)
        if "credit_code" in resolved:
            credit_code = resolved["credit_code"]
            company_name = name_index.get(credit_code, company_name)
        elif resolved.get("action") in ("ambiguous", "not_found"):
            return resolved

    # 有信用代码但没有企业名称 → 从名称索引反查
    if credit_code and not company_name:
        company_name = name_index.get(credit_code, "")

    # 如果没有企业信息，返回提示
    if not credit_code and not company_name:
        return {
            "action": "not_found",
            "message": "请先指定要开户的企业。您可以提供企业名称或统一信用代码。",
        }

    # 创建开户申请
    app_data = _load_account_data()
    app_id = f"app-{uuid.uuid4().hex[:12]}"
    now = datetime.now().isoformat()

    app_data["applications"][app_id] = {
        "id": app_id,
        "user_id": user_id,
        "conversation_id": conversation_id,
        "company_name": company_name,
        "credit_code": credit_code,
        "status": "upload",
        "documents": {},
        "form_data": {},
        "created_at": now,
        "submitted_at": None,
    }
    _save_account_data(app_data)

    # 返回上传链接（携带企业名称、信用代码、会话ID供 H5 页面使用）
    from urllib.parse import quote
    upload_url = f"{base_url}/h5/account-upload.html?app_id={app_id}&company_name={quote(company_name)}&credit_code={quote(credit_code)}&conversation_id={quote(conversation_id)}"

    return {
        "action": "result",
        "app_id": app_id,
        "company_name": company_name,
        "credit_code": credit_code,
        "status": "upload",
        "upload_url": upload_url,
        "required_documents": [
            "营业执照正本（清晰扫描件或照片）",
            "法定代表人身份证（正反面）",
            "公章印模（如有）",
        ],
    }


# ============================================================
# Skill 注册
# ============================================================

skill_registry.register(
    Skill(
        name="open_corporate_account",
        description=(
            "当用户表示客户已同意办理开户、需要协助开户时调用此技能。"
            "例如「客户已同意办理开户，请协助办理」「帮我给XX企业开户」「准备开户资料」等。"
            "返回资料上传链接，支持客户经理上传营业执照等文件后自动预填开户信息。"
        ),
        handler=handle_account_opening,
        parameters={
            "company_name": {
                "type": "string",
                "description": "企业名称（可选，可从上下文自动获取）",
                "required": False,
                "example": "北京星河科技有限公司",
            },
            "credit_code": {
                "type": "string",
                "description": "企业统一信用代码（可选）",
                "required": False,
                "example": "91110108MA01B3XK2P",
            },
        },
    )
)
