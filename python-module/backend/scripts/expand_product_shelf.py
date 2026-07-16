#!/usr/bin/env python3.13
# -*- coding: utf-8 -*-
"""
扩充产品货架：
1. 为现有产品添加结构化匹配字段 (match_keywords, amount/term ranges, risk_level, liquidity)
2. 新增 5 款存款/理财类产品
"""
import json
from pathlib import Path

DATA_DIR = Path(__file__).parent.parent / "data"
FILE = DATA_DIR / "product_recommendations.json"

with open(FILE, "r", encoding="utf-8") as f:
    data = json.load(f)

products = data["products"]

# ============================================================
# 1. 为现有产品添加匹配字段
# ============================================================
match_fields = {
    "tech_loan": {
        "match_keywords": ["科技", "高新技术", "专精特新", "融资", "贷款", "信贷"],
        "min_amount": 100, "max_amount": 5000,
        "min_term_days": 180, "max_term_days": 1825,
        "risk_level": "medium",
        "liquidity": "medium",
    },
    "equipment_lease": {
        "match_keywords": ["设备", "采购", "购置", "融资租赁", "生产线", "机器"],
        "min_amount": 500, "max_amount": 50000,
        "min_term_days": 365, "max_term_days": 2920,
        "risk_level": "medium",
        "liquidity": "low",
    },
    "supply_chain_finance": {
        "match_keywords": ["应收账款", "供应链", "保理", "核心企业", "上游", "供应商"],
        "min_amount": 100, "max_amount": 10000,
        "min_term_days": 30, "max_term_days": 365,
        "risk_level": "medium",
        "liquidity": "high",
    },
    "cross_border": {
        "match_keywords": ["跨境", "外贸", "进出口", "汇率", "外币", "国际", "结汇", "购汇"],
        "min_amount": 500, "max_amount": 100000,
        "min_term_days": 1, "max_term_days": 365,
        "risk_level": "medium",
        "liquidity": "medium",
    },
    "green_finance": {
        "match_keywords": ["绿色", "环保", "清洁能源", "节能", "碳减排", "新能源", "光伏"],
        "min_amount": 500, "max_amount": 50000,
        "min_term_days": 365, "max_term_days": 5475,
        "risk_level": "medium",
        "liquidity": "low",
    },
    "working_capital": {
        "match_keywords": ["流动资金", "周转", "营运", "日常经营", "循环贷款", "随借随还"],
        "min_amount": 100, "max_amount": 10000,
        "min_term_days": 1, "max_term_days": 365,
        "risk_level": "medium",
        "liquidity": "high",
    },
    "bond_underwriting": {
        "match_keywords": ["债券", "承销", "中期票据", "融资券", "发债", "直接融资"],
        "min_amount": 10000, "max_amount": 500000,
        "min_term_days": 365, "max_term_days": 3650,
        "risk_level": "low",
        "liquidity": "low",
    },
    "structured_deposit": {
        "match_keywords": ["结构性存款", "保本", "理财", "闲置资金", "收益", "稳健"],
        "min_amount": 50, "max_amount": 50000,
        "min_term_days": 30, "max_term_days": 365,
        "risk_level": "low",
        "liquidity": "low",
    },
    "sme_quick_loan": {
        "match_keywords": ["小微", "快捷贷", "快速放款", "小额", "创业"],
        "min_amount": 10, "max_amount": 1000,
        "min_term_days": 30, "max_term_days": 1095,
        "risk_level": "medium",
        "liquidity": "medium",
    },
    "ma_loan": {
        "match_keywords": ["并购", "收购", "扩张", "股权", "交易"],
        "min_amount": 5000, "max_amount": 200000,
        "min_term_days": 365, "max_term_days": 2555,
        "risk_level": "medium",
        "liquidity": "low",
    },
    "cash_management": {
        "match_keywords": ["现金管理", "资金归集", "集团", "资金池", "ERP", "银企直连"],
        "min_amount": 1000, "max_amount": None,
        "min_term_days": None, "max_term_days": None,
        "risk_level": "low",
        "liquidity": "high",
    },
    "ipo_service": {
        "match_keywords": ["上市", "IPO", "辅导", "股权", "战略投资"],
        "min_amount": None, "max_amount": None,
        "min_term_days": None, "max_term_days": None,
        "risk_level": "medium",
        "liquidity": "low",
    },
    "bill_pool": {
        "match_keywords": ["票据", "银承", "商承", "托收", "贴现", "承兑汇票"],
        "min_amount": 100, "max_amount": 50000,
        "min_term_days": 1, "max_term_days": 365,
        "risk_level": "low",
        "liquidity": "medium",
    },
}

for key, fields in match_fields.items():
    if key in products:
        products[key].update(fields)

# ============================================================
# 2. 新增产品
# ============================================================
new_products = {
    "notification_deposit": {
        "product_name": "通知存款",
        "category": "对公存款",
        "priority": "medium",
        "features": [
            "1天/7天通知存款两种选择",
            "年化利率1.00%-1.75%（7天通知）",
            "50万元起存，大额不限上限",
            "提前1天/7天通知即可支取，灵活性高",
        ],
        "application_period": "T+0开户起息",
        "target_profile": "有大额短期闲置资金、需要灵活支取的企业",
        "match_keywords": ["通知存款", "短期闲置", "灵活支取", "随时取出", "短期理财", "活期替代"],
        "min_amount": 50, "max_amount": None,
        "min_term_days": 1, "max_term_days": 365,
        "risk_level": "low",
        "liquidity": "high",
    },
    "certificate_of_deposit": {
        "product_name": "大额存单",
        "category": "对公存款",
        "priority": "medium",
        "features": [
            "保本保息，存款保险保障",
            "利率较普通定期上浮20-40BP",
            "期限丰富：1个月/3个月/6个月/1年/2年/3年",
            "可转让、可质押，流动性较好",
        ],
        "application_period": "T+0起息",
        "target_profile": "有确定性期限闲置资金、追求保本收益的企业",
        "match_keywords": ["大额存单", "定期存款", "保本保息", "固定期限", "利率上浮", "安全"],
        "min_amount": 1000, "max_amount": None,
        "min_term_days": 30, "max_term_days": 1095,
        "risk_level": "low",
        "liquidity": "medium",
    },
    "agreement_deposit": {
        "product_name": "协定存款",
        "category": "对公存款",
        "priority": "low",
        "features": [
            "基本额度按活期计息，超额部分按协定利率计息",
            "协定利率约1.15%-1.45%",
            "无需提前通知，随时可取",
            "兼顾流动性与收益性",
        ],
        "application_period": "T+0签约起息",
        "target_profile": "账户日均余额较大、需要兼顾流动性和收益的企业",
        "match_keywords": ["协定存款", "活期替代", "日均余额", "自动理财", "超额利息"],
        "min_amount": 50, "max_amount": None,
        "min_term_days": None, "max_term_days": None,
        "risk_level": "low",
        "liquidity": "high",
    },
    "corporate_wealth_mgmt": {
        "product_name": "对公理财产品",
        "category": "对公理财",
        "priority": "medium",
        "features": [
            "非保本浮动收益，预期年化2.5%-4.5%",
            "期限灵活：7天/14天/1个月/3个月/6个月/1年",
            "起购金额100万元",
            "投资范围：货币市场、债券、非标等",
        ],
        "application_period": "T+1起息",
        "target_profile": "有一定风险承受能力、追求较高收益的企业",
        "match_keywords": ["理财产品", "收益最高", "投资", "增值", "非保本", "预期收益"],
        "min_amount": 100, "max_amount": None,
        "min_term_days": 7, "max_term_days": 365,
        "risk_level": "medium",
        "liquidity": "low",
    },
    "money_market_fund": {
        "product_name": "现金管理类产品（类货基）",
        "category": "现金管理",
        "priority": "medium",
        "features": [
            "T+0赎回，实时到账（限额内）",
            "预期年化1.5%-2.5%",
            "1元起购，无申购赎回费",
            "底层资产为货币市场工具，风险极低",
        ],
        "application_period": "T+0确认",
        "target_profile": "需要高流动性、类活期体验、但追求高于活期收益的企业",
        "match_keywords": ["货币基金", "现金管理", "T+0", "随时赎回", "活期", "类活期", "灵活"],
        "min_amount": 1, "max_amount": None,
        "min_term_days": None, "max_term_days": None,
        "risk_level": "low",
        "liquidity": "high",
    },
}

products.update(new_products)

# ============================================================
# Save
# ============================================================
with open(FILE, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print(f"✅ 产品货架扩充完成：共 {len(products)} 款产品")
for k, v in products.items():
    has_match = "match_keywords" in v
    marker = "🆕" if k in new_products else ("✓" if has_match else "⚠️")
    print(f"  {marker} {k}: {v['product_name']}")
