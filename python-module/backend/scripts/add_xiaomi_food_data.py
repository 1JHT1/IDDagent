#!/usr/bin/env python3.13
# -*- coding: utf-8 -*-
"""
为「小米食品有限公司」补充完整测试数据
"""
import json
from pathlib import Path

DATA_DIR = Path(__file__).parent.parent / "data"

def _load(name: str) -> dict:
    p = DATA_DIR / name
    with open(p, "r", encoding="utf-8") as f:
        return json.load(f)

def _save(name: str, data: dict):
    with open(DATA_DIR / name, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

CC = "91440300MA5DCTJHQA3"
NAME = "小米食品有限公司"
INDUSTRY = "食品加工"

PROFILE = (
    "小米食品有限公司成立于2017年，注册资本5000万元，"
    "是一家专注于休闲食品研发、生产和销售的中型食品企业。"
    "公司拥有3个现代化生产基地，员工600余人，产品覆盖全国2000+零售终端，"
    "2025年营收约4.5亿元，年增长率15%。"
)

# ── 1. company_name_index.json ──
idx = _load("company_name_index.json")
idx[CC] = NAME
_save("company_name_index.json", idx)
print("✓ company_name_index.json")

# ── 2. risk_check.json ──
risk = _load("risk_check.json")
risk[CC] = {
    "credit_code": CC,
    "company_name": NAME,
    "has_risk": True,
    "risk_level": "medium",
    "risk_summary": "该企业存在食品安全行政处罚记录（2024年一次产品抽检不合格），注册资本实缴比例60%，综合风险等级为中等，建议加强尽调后受理。",
    "details": {
        "business_info": {"name": "工商信息", "items": [
            {"name": "企业基本信息", "result": "正常", "has_risk": False, "detail": "企业基本信息完整有效"},
            {"name": "营业执照期限", "result": "正常", "has_risk": False, "detail": "营业执照有效期至2047年"},
            {"name": "注册地址与经营地址", "result": "正常", "has_risk": False, "detail": "地址一致"},
            {"name": "经营范围变更", "result": "正常", "has_risk": False, "detail": "近2年未发生重大经营范围变更"},
            {"name": "法人代表信息", "result": "正常", "has_risk": False, "detail": "法人信息稳定，近5年未变更"},
            {"name": "股东及出资信息", "result": "关注", "has_risk": True, "detail": "注册资本实缴比例60%"},
            {"name": "主要人员变更", "result": "正常", "has_risk": False, "detail": "主要人员稳定"},
            {"name": "经营异常名录", "result": "无记录", "has_risk": False, "detail": "未列入经营异常名录"},
            {"name": "行政处罚信息", "result": "关注", "has_risk": True, "detail": "2024年因产品抽检不合格被市场监管局行政处罚"},
            {"name": "分支机构情况", "result": "正常", "has_risk": False, "detail": "设有3个生产基地分公司"},
        ]},
        "aml": {"name": "反洗钱", "items": [
            {"name": "客户身份识别", "result": "正常", "has_risk": False, "detail": "客户身份信息完整"},
            {"name": "受益所有人识别", "result": "正常", "has_risk": False, "detail": "受益所有人信息清晰"},
            {"name": "交易对手筛查", "result": "正常", "has_risk": False, "detail": "未发现高风险交易对手"},
            {"name": "制裁名单筛查", "result": "未命中", "has_risk": False, "detail": "未命中任何制裁名单"},
            {"name": "负面舆情信息", "result": "关注", "has_risk": True, "detail": "2024年产品抽检事件有少量媒体报道，影响有限"},
            {"name": "可疑交易报送", "result": "无记录", "has_risk": False, "detail": "无可疑交易记录"},
            {"name": "跨境交易审查", "result": "正常", "has_risk": False, "detail": "少量进口原材料交易，风险可控"},
            {"name": "客户洗钱风险评估", "result": "中风险", "has_risk": False, "detail": "综合评分42分"},
        ]},
        "risk_level": {"name": "风险等级", "items": [
            {"name": "综合风险等级", "result": "中", "has_risk": True, "detail": "基于28项指标综合评分，风险等级为：中"},
            {"name": "工商风险评分", "result": "42/100", "has_risk": False, "detail": "工商信息维度得分42分"},
            {"name": "反洗钱风险评分", "result": "42/100", "has_risk": False, "detail": "反洗钱维度得分42分"},
            {"name": "信用风险评分", "result": "42/100", "has_risk": False, "detail": "信用维度得分42分"},
            {"name": "经营稳定性评估", "result": "正常", "has_risk": False, "detail": "企业经营稳定，营收持续增长"},
            {"name": "开户受理建议", "result": "建议加强尽调后受理", "has_risk": True, "detail": "建议加强尽调后受理"},
            {"name": "尽职调查要求", "result": "增强型", "has_risk": True, "detail": "根据风险等级确定尽职调查类型：增强型"},
            {"name": "复核审批要求", "result": "需主管审批", "has_risk": True, "detail": "根据风险等级确定审批层级"},
            {"name": "开户后监控要求", "result": "加强监控", "has_risk": True, "detail": "根据风险等级确定监控频次"},
            {"name": "关联风险提示", "result": "不存在", "has_risk": False, "detail": "关联企业无风险记录"},
        ]},
    },
}
_save("risk_check.json", risk)
print("✓ risk_check.json")

# ── 3. customer_outreach.json ──
outreach = _load("customer_outreach.json")
outreach[CC] = {
    "credit_code": CC,
    "company_name": NAME,
    "business_address": "广东省东莞市松山湖科技产业园区工业南路8号",
    "registered_address": "广东省东莞市松山湖科技产业园区工业南路8号",
    "contact_channels": [
        {"type": "上下游合作", "relation": "该企业主要原材料供应商「东莞粮油集团」为我行授信客户，年交易额超1亿元",
         "contact_method": "可通过供应链金融部门联动对接，或联系我行松山湖支行客户经理 黄志强", "priority": "high"},
        {"type": "行业活动", "relation": "该企业总经理参加每年广州国际食品展览会",
         "contact_method": "可通过展会期间的银行金融服务站对接", "priority": "medium"},
        {"type": "园区合作", "relation": "松山湖科技产业园为我行战略合作园区",
         "contact_method": "可通过园区管理方引荐", "priority": "medium"},
    ],
    "insights": {
        "company_profile": PROFILE,
        "recent_news": [
            {"date": "2026-05-20", "title": f"{NAME}新品「小米锅巴Pro」上市首月销量突破500万袋", "source": "食品行业日报"},
            {"date": "2026-03-15", "title": f"{NAME}第三生产基地投产，年产能提升至10万吨", "source": "东莞日报"},
            {"date": "2026-01-08", "title": f"{NAME}获评「广东省农业产业化重点龙头企业」", "source": "广东省农业农村厅"},
        ],
        "industry_analysis": "食品加工行业属于刚需消费赛道，该企业在中南地区市场份额稳步提升，拥有3个现代化生产基地，预计有设备更新和流动资金需求。",
    },
    "scripts": {
        "approach": "以「流动资金循环贷款」和「供应链金融」为核心，结合食品行业季节性强特点推介灵活融资方案。",
        "talking_points": [
            f"您好，了解到{NAME}近年来发展迅速，新品市场反响很好，第三个生产基地也投产了，恭喜！",
            "食品企业有明显的季节性采购需求，我行「流动资金循环贷款」一次授信循环使用，可随借随还，非常契合贵司的经营节奏。",
            "另外，贵司的主要供应商「东莞粮油集团」是我行合作客户，我们可以通过供应链金融方案为贵司提供更优惠的采购融资。",
        ],
        "value_props": [
            "流动资金循环贷款：一次授信，随借随还",
            "供应链金融：基于核心企业信用的采购融资",
            "设备融资租赁：新基地设备投入的融资方案",
        ],
    },
}
_save("customer_outreach.json", outreach)
print("✓ customer_outreach.json")

# ── 4. product_recommendations.json ──
prod = _load("product_recommendations.json")
prod["recommendations"][CC] = {
    "credit_code": CC,
    "company_name": NAME,
    "analysis_summary": "该企业为食品加工行业中型企业，年营收约4.5亿元，拥有3个生产基地，处于稳定增长期。季节性采购和新基地运营带来持续的资金需求。",
    "recommendations": [
        {"key": "working_capital", "priority": "high",
         "reason": "食品企业季节性采购需求大，原材料备货占用资金多。循环贷款可灵活匹配采购节奏，按实际使用天数计息。",
         "expected_amount": "3000-5000万"},
        {"key": "supply_chain_finance", "priority": "high",
         "reason": "上游供应商「东莞粮油集团」为我行授信客户，可通过供应链金融实现采购融资，提升议价能力。",
         "expected_amount": "2000-4000万"},
        {"key": "equipment_lease", "priority": "medium",
         "reason": "第三生产基地刚投产，后续可能有新设备采购或产线升级需求。",
         "expected_amount": "1000-3000万"},
        {"key": "bill_pool", "priority": "low",
         "reason": "食品企业下游零售商多以票据结算，票据池可统一管理、高效融资。",
         "expected_amount": "—"},
        {"key": "structured_deposit", "priority": "low",
         "reason": "企业有阶段性闲置资金，可通过结构性存款提升收益。",
         "expected_amount": "500-1500万"},
    ],
}
_save("product_recommendations.json", prod)
print("✓ product_recommendations.json")

print(f"\n✅ 小米食品有限公司 ({CC}) 全维度数据已添加")
