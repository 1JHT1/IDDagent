# -*- coding: utf-8 -*-
"""
产品智能匹配技能插件
当用户描述具体的资金需求场景时，Coordinator 识别意图后调用此技能。
采用混合模式：规则引擎提取结构化需求 → LLM 智能匹配产品货架。
"""
import json
import os
import re
from pathlib import Path
from typing import Optional

from . import Skill, skill_registry
from .risk_check import _load_json, _fuzzy_match_company, _resolve_company_match

DATA_DIR = Path(__file__).parent.parent / "data"
PRODUCT_FILE = DATA_DIR / "product_recommendations.json"
NAME_INDEX_FILE = DATA_DIR / "company_name_index.json"

COORDINATOR_MODEL = os.getenv("COORDINATOR_MODEL", "deepseek-chat")
_client = None


def _get_llm_client():
    global _client
    if _client is None:
        from openai import AsyncOpenAI
        api_key = os.getenv("DEEPSEEK_API_KEY", "")
        base_url = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
        _client = AsyncOpenAI(api_key=api_key, base_url=base_url + "/v1")
    return _client


# ============================================================
# 规则引擎：结构化需求提取
# ============================================================

# 中文数字映射
_CN_NUM_MAP = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10, "两": 2}
_CN_NUM_PATTERN = "[一二三四五六七八九十两]"  # 只匹配中文数字，不匹配任意汉字

# 金额模式（单位：万元）
_AMOUNT_PATTERNS = [
    (r"(\d+(?:\.\d+)?)\s*亿", lambda m: float(m) * 10000),
    (r"(\d+(?:\.\d+)?)\s*千万", lambda m: float(m) * 1000),
    (r"(\d+(?:\.\d+)?)\s*百万", lambda m: float(m) * 100),
    (r"(\d+(?:\.\d+)?)\s*万", lambda m: float(m)),
    (f"({_CN_NUM_PATTERN})\\s*(?:个)?\\s*亿", lambda m: _CN_NUM_MAP.get(m, 0) * 10000),
    (f"({_CN_NUM_PATTERN})\\s*(?:个)?\\s*千万", lambda m: _CN_NUM_MAP.get(m, 0) * 1000),
    (f"({_CN_NUM_PATTERN})\\s*(?:个)?\\s*万", lambda m: _CN_NUM_MAP.get(m, 0)),
]

# 期限模式（单位：天）——注意：半年/半月要在数字模式前面
_DURATION_PATTERNS = [
    (r"半\s*年", lambda _: 180),
    (r"半\s*个?月", lambda _: 15),
    (r"(\d+(?:\.\d+)?)\s*年", lambda m: int(float(m) * 365)),
    (r"(\d+(?:\.\d+)?)\s*个?月", lambda m: int(float(m) * 30)),
    (r"(\d+(?:\.\d+)?)\s*周", lambda m: int(float(m) * 7)),
    (r"(\d+(?:\.\d+)?)\s*天", lambda m: int(float(m))),
    (r"(\d+(?:\.\d+)?)\s*日", lambda m: int(float(m))),
    (f"({_CN_NUM_PATTERN})\\s*年", lambda m: int(_CN_NUM_MAP.get(m, 0) * 365)),
    (f"({_CN_NUM_PATTERN})\\s*个?月", lambda m: int(_CN_NUM_MAP.get(m, 0) * 30)),
    (f"({_CN_NUM_PATTERN})\\s*周", lambda m: int(_CN_NUM_MAP.get(m, 0) * 7)),
]

# 风险偏好关键词
_RISK_KEYWORDS = {
    "low": ["保本", "无风险", "安全", "稳健", "零风险", "低风险", "保息", "存款保险"],
    "medium": ["稳健增值", "平衡", "适度风险", "中等风险"],
    "high": ["高收益", "激进", "高风险", "进取"],
}

# 收益追求关键词
_RETURN_KEYWORDS = ["收益最高", "收益最大化", "最高收益", "收益优先", "回报最高", "赚更多", "增值"]

# 流动性关键词
_LIQUIDITY_HIGH_KEYWORDS = ["随时取出", "随时支取", "灵活支取", "随时赎回", "活期", "T+0", "即时", "灵活管理", "随时", "灵活"]
_LIQUIDITY_MEDIUM_KEYWORDS = ["停留", "闲置", "暂存", "短期"]

# 用途关键词
_PURPOSE_KEYWORDS = [
    "购买原材料", "采购", "备货", "设备采购", "设备更新", "扩建", "扩张",
    "工程", "工程款", "项目款", "投资", "并购", "收购", "日常经营",
    "发工资", "缴税", "偿还贷款", "还贷", "支付货款",
]

# 资金方向关键词
_INVESTMENT_KEYWORDS = [
    "入账", "到账", "收到", "回款", "闲置", "停留", "暂存", "暂放",
    "闲置资金", "收益最高", "收益最大化", "理财", "保值", "增值",
    "存款", "存单", "短期理财", "资金管理",
]
_BORROWING_KEYWORDS = [
    "贷款", "借款", "融资", "信贷", "授信", "借钱", "资金不足",
    "资金缺口", "需要资金", "周转困难", "资金需求", "贷款额度",
]


def _classify_fund_direction(text: str) -> str:
    """判断资金方向: investment(有资金要理财) / borrowing(需要借钱) / both(不明确)"""
    has_invest = any(kw in text for kw in _INVESTMENT_KEYWORDS)
    has_borrow = any(kw in text for kw in _BORROWING_KEYWORDS)
    if has_invest and not has_borrow:
        return "investment"
    elif has_borrow and not has_invest:
        return "borrowing"
    return "both"


# 产品分类映射（用于预筛选）
_INVESTMENT_CATEGORIES = {"对公理财", "对公存款", "现金管理"}
_LENDING_CATEGORIES = {"对公信贷", "融资租赁", "供应链金融"}


def _filter_products_by_direction(products: dict, fund_direction: str) -> dict:
    """根据资金方向预筛选产品"""
    if fund_direction == "investment":
        return {k: v for k, v in products.items() if v["category"] not in _LENDING_CATEGORIES}
    elif fund_direction == "borrowing":
        return {k: v for k, v in products.items() if v["category"] not in _INVESTMENT_CATEGORIES}
    return products  # both: 不筛选


def _extract_amounts(text: str) -> list[float]:
    """从文本中提取所有金额（万元）"""
    amounts = []
    for pattern, converter in _AMOUNT_PATTERNS:
        for m in re.finditer(pattern, text):
            val = converter(m.group(1))
            if val > 0:
                amounts.append(val)
    # 去重并排序（降序）
    return sorted(set(amounts), reverse=True)


def _extract_duration(text: str) -> Optional[int]:
    """从文本中提取期限（天）"""
    for pattern, converter in _DURATION_PATTERNS:
        m = re.search(pattern, text)
        if m:
            try:
                return converter(m.group(1))
            except IndexError:
                return converter(None)
    return None


def _extract_risk_preference(text: str) -> str:
    """提取风险偏好"""
    for level, keywords in _RISK_KEYWORDS.items():
        for kw in keywords:
            if kw in text:
                return level
    # 默认中等
    return "medium"


def _extract_needs(text: str) -> dict:
    """从用户文本中提取结构化需求"""
    amounts = _extract_amounts(text)
    duration = _extract_duration(text)

    # 取最大金额为总金额，次大为可用金额
    total_amount = amounts[0] if len(amounts) > 0 else None
    available_amount = amounts[1] if len(amounts) > 1 else (amounts[0] if len(amounts) == 1 else None)

    # 风险偏好
    risk_pref = _extract_risk_preference(text)

    # 收益追求
    return_priority = any(kw in text for kw in _RETURN_KEYWORDS)

    # 流动性需求
    liquidity = "medium"
    if any(kw in text for kw in _LIQUIDITY_HIGH_KEYWORDS):
        liquidity = "high"
    elif any(kw in text for kw in _LIQUIDITY_MEDIUM_KEYWORDS):
        liquidity = "medium"

    # 用途
    purpose = None
    for kw in _PURPOSE_KEYWORDS:
        if kw in text:
            purpose = kw
            break

    return {
        "total_amount": total_amount,
        "available_amount": available_amount,
        "duration_days": duration,
        "purpose": purpose,
        "risk_preference": risk_pref,
        "liquidity_need": liquidity,
        "return_priority": return_priority,
        "fund_direction": _classify_fund_direction(text),
    }


# ============================================================
# LLM 智能匹配引擎
# ============================================================

_MATCH_SYSTEM_PROMPT = """你是一个银行金融产品智能匹配顾问。根据客户的具体资金需求，从产品货架中挑选最合适的产品，并给出推荐理由和产品亮点。

## 任务
1. 根据客户需求和画像，从产品货架中筛选最匹配的产品（最多3个）
2. 为每个匹配产品给出匹配度评分（0-100）和推荐理由
3. 计算预估收益（如适用）
4. 提取产品亮点（3-5个关键点）

## 输出格式（严格 JSON，不要包裹代码块）
{
  "needs_summary": "一句话总结客户需求",
  "matches": [
    {
      "product_key": "产品key",
      "match_score": 95,
      "reason": "推荐理由（2-3句话，说明为什么匹配）",
      "highlights": ["亮点1", "亮点2", "亮点3"],
      "estimated_return": "预估收益（如适用，否则留空）"
    }
  ]
}

## 评分标准
- 90-100: 完美匹配（金额、期限、风险、流动性全部契合）
- 75-89: 高度匹配（大部分条件契合）
- 60-74: 部分匹配（可推荐但有一定限制）
- <60: 不推荐

## 注意事项
- 只输出 JSON，不要输出其他文本
- 按 match_score 降序排列
- 推荐理由要具体、有数据支撑
- 预估收益要基于实际利率计算
- 产品货架已根据客户资金方向预筛选：如果是投资类场景（有资金入账/闲置），货架中不含贷款产品；如果是融资类场景，货架中不含理财/存款产品。请严格按照货架中的产品推荐，不要建议客户使用不在货架中的产品类型。"""


def _build_match_prompt(needs: dict, products: dict, company_profile: Optional[dict] = None) -> str:
    """构建 LLM 匹配提示词"""
    lines = []

    # 客户需求
    lines.append("## 客户需求")
    if needs["total_amount"]:
        lines.append(f"- 总金额: {needs['total_amount']}万元")
    if needs["available_amount"]:
        lines.append(f"- 可用金额: {needs['available_amount']}万元")
    if needs["duration_days"]:
        lines.append(f"- 资金停留期限: {needs['duration_days']}天")
    if needs["purpose"]:
        lines.append(f"- 资金用途: {needs['purpose']}")
    lines.append(f"- 风险偏好: {needs['risk_preference']}")
    lines.append(f"- 流动性需求: {needs['liquidity_need']}")
    lines.append(f"- 收益优先: {'是' if needs['return_priority'] else '否'}")

    # 企业画像
    if company_profile:
        lines.append("\n## 企业画像")
        lines.append(f"- 企业名称: {company_profile.get('company_name', '未知')}")
        lines.append(f"- 所属行业: {company_profile.get('industry', '未知')}")
        if company_profile.get("insights"):
            profile_text = company_profile["insights"].get("company_profile", "")
            if profile_text:
                lines.append(f"- 企业概况: {profile_text}")

    # 产品货架
    lines.append("\n## 产品货架")
    for key, prod in products.items():
        term_info = ""
        if prod.get("min_term_days") is not None:
            term_info += f", 期限{prod['min_term_days']}-{prod['max_term_days']}天"
        amount_info = ""
        if prod.get("min_amount") is not None:
            max_str = f"-{prod['max_amount']}" if prod.get("max_amount") else "+"
            amount_info = f", 金额{prod['min_amount']}{max_str}万"
        lines.append(
            f"- **{key}** ({prod['product_name']}): {prod['category']}, "
            f"风险{prod['risk_level']}, 流动性{prod['liquidity']}"
            f"{amount_info}{term_info}"
        )
        lines.append(f"  特点: {'; '.join(prod['features'])}")

    return "\n".join(lines)


async def _llm_match_products(needs: dict, products: dict, company_profile: Optional[dict] = None) -> dict:
    """调用 LLM 进行智能产品匹配"""
    client = _get_llm_client()
    user_prompt = _build_match_prompt(needs, products, company_profile)

    response = await client.chat.completions.create(
        model=COORDINATOR_MODEL,
        messages=[
            {"role": "system", "content": _MATCH_SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        temperature=0.3,
        max_tokens=1500,
    )

    text = response.choices[0].message.content or ""

    # 解析 JSON
    json_match = re.search(r"\{[\s\S]*\}", text)
    if json_match:
        try:
            return json.loads(json_match.group(0))
        except json.JSONDecodeError:
            pass

    return {
        "needs_summary": "需求解析完成，但产品匹配结果返回异常",
        "matches": [],
    }


# ============================================================
# 加载企业画像
# ============================================================

def _load_company_profile(credit_code: str, company_name: str) -> Optional[dict]:
    """加载企业画像（综合多个数据源）"""
    if not credit_code and not company_name:
        return None

    profile = {"company_name": company_name, "credit_code": credit_code}

    # 尝试从 customer_outreach 获取企业概况
    try:
        outreach = _load_json(DATA_DIR / "customer_outreach.json")
        data = outreach.get(credit_code)
        if data:
            profile["company_name"] = data.get("company_name", company_name)
            profile["insights"] = data.get("insights")
    except Exception:
        pass

    # 尝试从 product_recommendations 获取行业分析
    if not profile.get("insights"):
        try:
            prod_data = _load_json(PRODUCT_FILE)
            recs = prod_data.get("recommendations", {}).get(credit_code)
            if recs:
                profile["company_name"] = recs.get("company_name", company_name)
                profile["industry"] = recs.get("analysis_summary", "")
        except Exception:
            pass

    return profile if profile.get("company_name") else None


# ============================================================
# Skill Handler
# ============================================================

async def handle_product_match(user_id: str, params: dict) -> dict:
    """
    产品智能匹配处理

    params:
        query (必选): 用户需求描述
        company_name (可选): 企业名称
        credit_code (可选): 统一信用代码
    """
    query = params.get("query", "").strip()
    company_name = params.get("company_name", "").strip()
    credit_code = params.get("credit_code", "").strip()

    if not query:
        return {
            "error": "请描述您的资金需求，我将为您智能匹配最合适的金融产品。",
        }

    # 1. 规则提取结构化需求
    needs = _extract_needs(query)

    # 2. 加载产品货架
    all_data = _load_json(PRODUCT_FILE)
    products = all_data.get("products", {})

    if not products:
        return {
            "error": "产品货架暂无数据，请联系管理员。",
        }

    # 2.5 根据资金方向预筛选产品
    fund_direction = needs.get("fund_direction", "both")
    filtered_products = _filter_products_by_direction(products, fund_direction)
    if not filtered_products:
        filtered_products = products  # 降级: 筛选后无产品则用全部
    print(f"[ProductMatch] 资金方向: {fund_direction}, 产品数: {len(products)} → {len(filtered_products)}")

    # 3. 加载企业画像（如有）
    company_profile = None

    # 解析企业名称 → 信用代码
    if company_name and not credit_code:
        name_index = _load_json(NAME_INDEX_FILE)
        resolved = _resolve_company_match(company_name, name_index)
        if "credit_code" in resolved:
            credit_code = resolved["credit_code"]
            company_name_match = resolved.get("company_name", company_name)
            company_name = company_name_match
        elif resolved.get("action") in ("ambiguous", "not_found"):
            # 企业名称歧义或未找到，仍然可以进行产品匹配（不带企业画像）
            pass

    if credit_code or company_name:
        company_profile = _load_company_profile(credit_code, company_name)

    # 4. LLM 智能匹配（使用预筛选后的产品）
    llm_result = await _llm_match_products(needs, filtered_products, company_profile)

    # 5. 组装标准化结果
    matches = llm_result.get("matches", [])
    enriched_matches = []
    for m in matches:
        prod_key = m.get("product_key", "")
        prod = products.get(prod_key, {})
        enriched_matches.append({
            "product_key": prod_key,
            "product_name": prod.get("product_name", prod_key),
            "category": prod.get("category", ""),
            "match_score": m.get("match_score", 0),
            "reason": m.get("reason", ""),
            "highlights": m.get("highlights", []),
            "estimated_return": m.get("estimated_return", ""),
            "features": prod.get("features", []),
            "application_period": prod.get("application_period", ""),
        })

    return {
        "action": "result",
        "needs_summary": llm_result.get("needs_summary", ""),
        "needs_detail": needs,
        "matches": enriched_matches,
        "company_name": company_profile.get("company_name") if company_profile else None,
        "credit_code": credit_code or None,
        "total_count": len(enriched_matches),
    }


# ============================================================
# Skill 注册
# ============================================================

skill_registry.register(
    Skill(
        name="match_products_intelligently",
        description=(
            "当用户描述了具体的资金需求场景时调用此技能，"
            "例如「客户有5千万工程款闲置一个月」「需要短期理财」「有一笔大额资金需要灵活管理」等。"
            "基于用户需求从产品货架中智能匹配最佳产品，给出推荐理由和产品亮点。"
            "注意：当用户仅说「为XX企业推荐产品」时，应使用 recommend_products 技能而非此技能。"
        ),
        handler=handle_product_match,
        parameters={
            "query": {
                "type": "string",
                "description": "用户需求描述文本",
                "required": True,
                "example": "客户最近有一笔5千万的工程款打入，会停留一个月后，需要抽出4千万购买原材料",
            },
            "company_name": {
                "type": "string",
                "description": "企业名称（可选）",
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
