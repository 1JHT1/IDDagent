# -*- coding: utf-8 -*-
"""
潜客推荐技能插件
当用户询问拓户/开户客户清单时，Coordinator 识别意图后调用此技能
"""
import json
import os
from pathlib import Path
from typing import Any

from . import Skill, skill_registry

# 数据文件路径
DATA_DIR = Path(__file__).parent.parent / "data"
SUMMARY_FILE = DATA_DIR / "potential_customers.json"
DETAILS_FILE = DATA_DIR / "potential_customer_details.json"
UPLOADED_FILE = DATA_DIR / "uploaded_customers.json"

# 用户自定义上传的 source_id
UPLOAD_SOURCE_ID = "user_upload"
UPLOAD_SOURCE_NAME = "用户自定义上传"


# ============================================================
# 数据读取
# ============================================================
def _load_json(filepath: Path) -> dict:
    """加载 JSON 数据"""
    if not filepath.exists():
        return {}
    with open(filepath, "r", encoding="utf-8") as f:
        return json.load(f)


# ============================================================
# 技能处理函数
# ============================================================
def _load_uploaded(user_id: str) -> list[dict]:
    """加载用户自定义上传的客户列表"""
    data = _load_json(UPLOADED_FILE)
    return data.get(user_id, [])


async def handle_potential_customer(user_id: str, params: dict) -> dict:
    """
    潜客推荐处理

    params:
        source_id (可选): 指定来源ID，不传则返回汇总清单
    """
    source_id = params.get("source_id")

    # ── 查看用户自定义上传的客户详情 ──
    if source_id == UPLOAD_SOURCE_ID:
        uploaded = _load_uploaded(user_id)
        uploaded.sort(key=lambda x: x.get("score", 0), reverse=True)
        return {
            "action": "detail",
            "source_id": source_id,
            "customers": uploaded,
        }

    if source_id:
        # 返回指定来源的客户详情
        details = _load_json(DETAILS_FILE)
        user_data = details.get(user_id, {})
        sources = user_data.get("sources", {})
        customer_list = sources.get(source_id, [])

        # 按得分降序排列
        customer_list.sort(key=lambda x: x.get("score", 0), reverse=True)
        return {
            "action": "detail",
            "source_id": source_id,
            "customers": customer_list,
        }

    # 返回客户清单汇总（合并内置来源 + 用户自定义上传来源）
    summary = _load_json(SUMMARY_FILE)
    user_data = summary.get(user_id, {})
    sources = list(user_data.get("sources", []))

    # 如果用户有上传数据，追加「用户自定义上传」来源
    uploaded = _load_uploaded(user_id)
    if uploaded:
        sources.append({
            "source_id": UPLOAD_SOURCE_ID,
            "source_name": UPLOAD_SOURCE_NAME,
            "customer_count": len(uploaded),
        })

    if not sources:
        return {
            "action": "summary",
            "sources": [],
            "message": "暂无拓户客户清单数据",
        }

    return {
        "action": "summary",
        "sources": sources,
    }


# ============================================================
# 注册技能
# ============================================================
skill_registry.register(
    Skill(
        name="recommend_corporate_customers",
        description=(
            "当用户询问推荐拓户（开户）客户清单、潜客名单、"
            "推荐开户客户、查看客户详情、上传客户清单、"
            "导入客户、上传Excel客户时调用此技能。"
            "获取基于用户画像的潜在对公开户客户列表，并展示上传入口。"
        ),
        handler=handle_potential_customer,
        parameters={
            "source_id": {
                "type": "string",
                "description": "客户清单来源ID，如要查看具体来源的客户明细则传入。可选值: corp_deposit_agent(对公存款智能体), user_upload(用户自定义上传)",
                "required": False,
                "example": "corp_deposit_agent",
            },
        },
    )
)
