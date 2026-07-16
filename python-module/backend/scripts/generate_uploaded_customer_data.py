#!/usr/bin/env python3.13
# -*- coding: utf-8 -*-
"""
为用户自定义上传的5家客户补充完整测试数据：
- uploaded_customers.json（更新得分）
- company_name_index.json（名称映射）
- risk_check.json（风险信息）
- customer_outreach.json（拓户准备）
- product_recommendations.json（产品推荐）
"""
import json
from pathlib import Path

DATA_DIR = Path(__file__).parent.parent / "data"


def _load(name: str) -> dict:
    p = DATA_DIR / name
    if p.exists():
        with open(p, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def _save(name: str, data: dict):
    with open(DATA_DIR / name, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


# ============================================================
# 5 家上传客户基础信息
# ============================================================
CUSTOMERS = {
    "91110108MA01B3XK2PB": {
        "name": "红米子集科技有限公司",
        "score": 88.50,
        "business_address": "北京市海淀区上地信息路26号中关村创业大厦9层",
        "registered_address": "北京市海淀区上地信息路26号",
        "risk": {"has_risk": False, "risk_level": "low",
                 "risk_summary": "该企业工商信息正常，无重大风险事项，反洗钱筛查未发现异常，建议正常受理开户业务。"},
        "industry": "智能硬件",
        "profile": "红米子集科技有限公司成立于2019年，注册资本3000万元，专注于智能家居硬件研发与销售。公司拥有研发团队80余人，年营收约1.2亿元，已获得12项发明专利。",
    },
    "91440300MA5DCTJH8NB": {
        "name": "木棉花供应链管理有限公司",
        "score": 82.30,
        "business_address": "深圳市南山区科技园南区高新南七道1号深圳国家工程实验室大楼B座5层",
        "registered_address": "深圳市南山区科技园南区高新南七道1号",
        "risk": {"has_risk": True, "risk_level": "medium",
                 "risk_summary": "该企业存在少量行政处罚记录（环保类），注册资本实缴比例偏低，综合风险等级为中等，建议加强尽职调查后受理。"},
        "industry": "供应链管理",
        "profile": "木棉花供应链管理有限公司成立于2016年，注册资本8000万元（实缴30%），是一家专注于电子元器件供应链整合的中型企业。公司服务客户超200家，年交易额约5亿元。",
    },
    "91440300MA5DCTJHQAZ": {
        "name": "叶子科技有限公司",
        "score": 75.60,
        "business_address": "深圳市龙岗区坂田街道天安云谷产业园4栋18层",
        "registered_address": "深圳市龙岗区坂田街道天安云谷产业园4栋",
        "risk": {"has_risk": False, "risk_level": "low",
                 "risk_summary": "企业工商信息完整有效，法人及主要股东信用良好，反洗钱筛查通过，风险等级低，建议正常受理。"},
        "industry": "SaaS服务",
        "profile": "叶子科技有限公司成立于2020年，注册资本1000万元，是一家面向中小企业提供SaaS协同办公平台的创业公司。目前服务企业超3000家，月活用户10万+，2025年营收约6000万元。",
    },
    "91440300MA5DCTJHQA1": {
        "name": "小米科技有限公司",
        "score": 92.15,
        "business_address": "北京市海淀区清河中街68号华润五彩城写字楼东区15层",
        "registered_address": "北京市海淀区清河中街68号",
        "risk": {"has_risk": False, "risk_level": "low",
                 "risk_summary": "企业工商信息正常，信用记录良好，反洗钱筛查未发现异常，综合风险等级低，建议正常受理开户业务。"},
        "industry": "消费电子",
        "profile": "小米科技有限公司成立于2010年，注册资本18.5亿元，是全球知名的消费电子和智能制造企业。公司年营收超3000亿元，全球员工超3万人，已上市（港交所：1810.HK）。",
    },
    "91440300MA5DCTJHQA2": {
        "name": "小米至善科技有限公司",
        "score": 79.40,
        "business_address": "北京市大兴区经济开发区荣华南路10号院5号楼6层",
        "registered_address": "北京市大兴区经济开发区荣华南路10号院",
        "risk": {"has_risk": True, "risk_level": "medium",
                 "risk_summary": "该企业成立时间较短（不足2年），注册资本实缴比例偏低，法人曾涉及民事纠纷（已结案），综合风险等级为中等，建议加强尽调。"},
        "industry": "环保科技",
        "profile": "小米至善科技有限公司成立于2024年，注册资本500万元（实缴20%），是一家专注于工业废水处理和环保设备研发的新兴企业。公司现有员工30余人，2025年营收约800万元。",
    },
}


# ============================================================
# 1. 更新 uploaded_customers.json 得分
# ============================================================
def update_uploaded_scores():
    data = _load("uploaded_customers.json")
    for uid, customers in data.items():
        for c in customers:
            cc = c.get("credit_code", "")
            if cc in CUSTOMERS:
                c["score"] = CUSTOMERS[cc]["score"]
    _save("uploaded_customers.json", data)
    print("✓ uploaded_customers.json — 得分已更新")


# ============================================================
# 2. 更新 company_name_index.json
# ============================================================
def update_name_index():
    data = _load("company_name_index.json")
    for cc, info in CUSTOMERS.items():
        data[cc] = info["name"]
    _save("company_name_index.json", data)
    print("✓ company_name_index.json — 5家企业名称映射已添加")


# ============================================================
# 3. 生成 risk_check.json
# ============================================================
def _build_risk_entry(cc: str, info: dict) -> dict:
    has_risk = info["risk"]["has_risk"]
    level = info["risk"]["risk_level"]
    summary = info["risk"]["risk_summary"]

    biz_items = [
        {"name": "企业基本信息", "result": "正常", "has_risk": False, "detail": "企业基本信息完整有效"},
        {"name": "营业执照期限", "result": "正常", "has_risk": False, "detail": "营业执照有效期充足"},
        {"name": "注册地址与经营地址", "result": "正常" if not has_risk else "关注", "has_risk": has_risk,
         "detail": "地址一致" if not has_risk else "注册地址与实际经营地址存在细微差异"},
        {"name": "经营范围变更", "result": "正常", "has_risk": False, "detail": "近2年未发生经营范围重大变更"},
        {"name": "法人代表信息", "result": "正常" if level != "medium" else "关注",
         "has_risk": level == "medium",
         "detail": "法人信息稳定" if level != "medium" else "法人曾涉及民事纠纷（已结案）"},
        {"name": "股东及出资信息", "result": "正常" if level != "medium" else "关注",
         "has_risk": level == "medium",
         "detail": "股东结构清晰" if level != "medium" else "注册资本实缴比例偏低"},
        {"name": "主要人员变更", "result": "正常", "has_risk": False, "detail": "主要人员稳定"},
        {"name": "经营异常名录", "result": "无记录" if not has_risk else "有记录",
         "has_risk": has_risk,
         "detail": "未列入经营异常名录" if not has_risk else "曾有环保处罚记录"},
        {"name": "行政处罚信息", "result": "无记录" if not has_risk else "关注",
         "has_risk": has_risk,
         "detail": "无行政处罚记录" if not has_risk else "有环保类行政处罚记录"},
        {"name": "分支机构情况", "result": "正常", "has_risk": False, "detail": "无分支机构"},
    ]

    aml_items = [
        {"name": "客户身份识别", "result": "正常" if not has_risk else "关注",
         "has_risk": has_risk,
         "detail": "客户身份信息完整" if not has_risk else "部分信息需补充核实"},
        {"name": "受益所有人识别", "result": "正常", "has_risk": False, "detail": "受益所有人信息清晰"},
        {"name": "交易对手筛查", "result": "正常", "has_risk": False, "detail": "未发现高风险交易对手"},
        {"name": "制裁名单筛查", "result": "未命中", "has_risk": False, "detail": "未命中任何制裁名单"},
        {"name": "负面舆情信息", "result": "正常" if not has_risk else "关注",
         "has_risk": has_risk,
         "detail": "无负面舆情" if not has_risk else "少量网络负面评论，影响有限"},
        {"name": "可疑交易报送", "result": "无记录", "has_risk": False, "detail": "无可疑交易记录"},
        {"name": "跨境交易审查", "result": "正常", "has_risk": False, "detail": "无跨境交易"},
        {"name": "客户洗钱风险评估", "result": "低风险" if not has_risk else "中风险",
         "has_risk": False,
         "detail": "综合评分低于30分" if not has_risk else "综合评分45分"},
    ]

    score_map = {"low": "25/100", "medium": "48/100", "high": "72/100"}
    level_cn = {"low": "低", "medium": "中", "high": "高"}
    suggestion = "建议正常受理" if not has_risk else "建议加强尽调后受理"
    dd_type = "标准型" if not has_risk else "增强型"

    risk_level_items = [
        {"name": "综合风险等级", "result": level_cn[level], "has_risk": has_risk,
         "detail": f"基于28项指标综合评分，风险等级为：{level_cn[level]}"},
        {"name": "工商风险评分", "result": score_map[level], "has_risk": level != "low",
         "detail": f"工商信息维度得分{score_map[level].split('/')[0]}分"},
        {"name": "反洗钱风险评分", "result": score_map[level], "has_risk": level != "low",
         "detail": f"反洗钱维度得分{score_map[level].split('/')[0]}分"},
        {"name": "信用风险评分", "result": score_map[level], "has_risk": level != "low",
         "detail": f"信用维度得分{score_map[level].split('/')[0]}分"},
        {"name": "经营稳定性评估", "result": "正常" if not has_risk else "关注",
         "has_risk": has_risk,
         "detail": "企业经营稳定" if not has_risk else "企业成立时间较短，经营稳定性待观察"},
        {"name": "开户受理建议", "result": suggestion, "has_risk": has_risk, "detail": suggestion},
        {"name": "尽职调查要求", "result": dd_type, "has_risk": has_risk, "detail": f"根据风险等级确定尽职调查类型：{dd_type}"},
        {"name": "复核审批要求", "result": "标准审批" if not has_risk else "需主管审批",
         "has_risk": has_risk, "detail": "根据风险等级确定审批层级"},
        {"name": "开户后监控要求", "result": "常规监控" if not has_risk else "加强监控",
         "has_risk": has_risk, "detail": "根据风险等级确定监控频次"},
        {"name": "关联风险提示", "result": "不存在" if not has_risk else "关注",
         "has_risk": has_risk,
         "detail": "关联企业无风险记录" if not has_risk else "关联企业存在少量风险记录"},
    ]

    return {
        "credit_code": cc,
        "company_name": info["name"],
        "has_risk": has_risk,
        "risk_level": level,
        "risk_summary": summary,
        "details": {
            "business_info": {"name": "工商信息", "items": biz_items},
            "aml": {"name": "反洗钱", "items": aml_items},
            "risk_level": {"name": "风险等级", "items": risk_level_items},
        },
    }


def update_risk_check():
    data = _load("risk_check.json")
    for cc, info in CUSTOMERS.items():
        data[cc] = _build_risk_entry(cc, info)
    _save("risk_check.json", data)
    print("✓ risk_check.json — 5家企业风险数据已添加")


# ============================================================
# 4. 生成 customer_outreach.json
# ============================================================
def _build_outreach_entry(cc: str, info: dict) -> dict:
    name = info["name"]
    industry = info["industry"]
    profile = info["profile"]

    # 根据企业特征定制触达渠道
    channels_map = {
        "91110108MA01B3XK2PB": [
            {"type": "关联客户", "relation": f"该企业是「北京星河科技有限公司」的智能家居硬件供应商",
             "contact_method": "可通过我行对公客户经理 张明辉 引荐联系", "priority": "high"},
            {"type": "行业活动", "relation": "该企业CEO参加每年深圳国际智能家居展览会",
             "contact_method": "可通过展会期间的银行金融服务站对接", "priority": "medium"},
        ],
        "91440300MA5DCTJH8NB": [
            {"type": "业务往来", "relation": "该企业近半年在我行深圳分行结算账户月均交易额超2000万元",
             "contact_method": "现有结算业务对接人：客户经理 王丽华（南山支行）", "priority": "high"},
            {"type": "上下游合作", "relation": "该企业上游供应商中有3家为我行授信客户",
             "contact_method": "可通过供应链金融部门进行联动对接", "priority": "medium"},
        ],
        "91440300MA5DCTJHQAZ": [
            {"type": "园区合作", "relation": "天安云谷产业园为我行战略合作园区，园区内企业享有专属金融优惠",
             "contact_method": "可通过园区管理方引荐，或联系我行坂田支行客户经理 陈志强", "priority": "high"},
        ],
        "91440300MA5DCTJHQA1": [
            {"type": "战略客户", "relation": "该企业为港股上市公司，是我行重点关注的战略级客户",
             "contact_method": "由总行公司金融部直接对接，负责人：总监 刘伟", "priority": "high"},
            {"type": "投行关系", "relation": "该企业2025年曾在我行承销发行过5亿元中期票据",
             "contact_method": "投资银行部客户经理 赵晓明 为主要对接人", "priority": "high"},
            {"type": "代发工资", "relation": "该企业北京分部2000+员工，目前代发工资在他行",
             "contact_method": "可通过代发工资业务切入合作", "priority": "medium"},
        ],
        "91440300MA5DCTJHQA2": [
            {"type": "政策扶持", "relation": "该企业入驻大兴经济开发区，享有园区绿色企业扶持政策",
             "contact_method": "可通过开发区管委会引荐，联系我行大兴支行客户经理 孙丽", "priority": "medium"},
        ],
    }

    channels = channels_map.get(cc, [])

    # 根据行业定制营销谈资
    news_templates = {
        "智能硬件": [
            {"date": "2026-05-15", "title": f"{name}发布新一代智能家居控制中枢，预售订单突破10万台", "source": "36氪"},
            {"date": "2026-03-20", "title": f"{name}入选工信部「专精特新」中小企业名单", "source": "工信部"},
        ],
        "供应链管理": [
            {"date": "2026-05-10", "title": f"{name}与华为签署供应链数字化合作协议", "source": "证券时报"},
            {"date": "2026-04-01", "title": f"{name}深圳仓库扩容至5万平方米，服务能力大幅提升", "source": "深圳商报"},
        ],
        "SaaS服务": [
            {"date": "2026-05-25", "title": f"{name}月活用户突破10万，获A轮融资5000万元", "source": "IT桔子"},
            {"date": "2026-03-12", "title": f"{name}入选深圳市高新技术企业培育库", "source": "深圳市科创委"},
        ],
        "消费电子": [
            {"date": "2026-06-01", "title": f"{name}2025年度财报发布，营收同比增长18%", "source": "港交所公告"},
            {"date": "2026-05-08", "title": f"{name}智能工厂项目二期投产，年产能提升至5000万台", "source": "新华社"},
            {"date": "2026-04-20", "title": f"{name}与多家银行签署银团贷款协议，总额50亿元", "source": "中国证券报"},
        ],
        "环保科技": [
            {"date": "2026-05-18", "title": f"{name}中标大兴区工业废水处理PPP项目，金额3200万元", "source": "中国政府采购网"},
            {"date": "2026-03-05", "title": f"{name}研发的膜分离废水处理技术获国家发明专利", "source": "国家知识产权局"},
        ],
    }

    news = news_templates.get(industry, [])

    # 根据行业定制分析
    analysis_map = {
        "智能硬件": f"{industry}行业处于稳定增长期，该企业在中关村核心区域运营，团队规模适中，预计有设备采购和流动资金需求。",
        "供应链管理": f"{industry}行业资金周转需求大，该企业年交易额约5亿元，上下游客户众多，预计有较强的供应链金融和流动资金贷款需求。",
        "SaaS服务": f"{industry}行业高速发展，该企业作为创业公司处于快速成长期，刚完成A轮融资，预计有资金管理和扩张融资需求。",
        "消费电子": f"{industry}行业巨头企业，营收超3000亿元，金融需求多元化。我行可围绕其供应链、跨境业务、员工代发等场景提供综合金融服务。",
        "环保科技": f"{industry}行业受政策支持力度大，该企业刚起步但已中标政府PPP项目，预计有设备采购和流动资金需求。",
    }

    approach_map = {
        "智能硬件": f"以「科技贷」和「设备融资租赁」为核心，结合智能家居行业特点推介我行科创金融方案。",
        "供应链管理": f"重点推介「供应链金融-应收账款融资」和「流动资金循环贷款」，匹配其高频资金周转需求。",
        "SaaS服务": f"以「小微企业快捷贷」和「科技贷」为切入点，配合SaaS行业轻资产特点设计信用融资方案。",
        "消费电子": f"围绕「并购贷款」「集团现金管理平台」「跨境金融服务方案」等提供一站式综合金融服务。",
        "环保科技": f"以「绿色金融专项贷」为核心，结合PPP项目回款周期设计灵活还款方案。",
    }

    talking_points_map = {
        "智能硬件": [
            f"您好，了解到{info['name']}在智能家居领域发展迅速，产品市场反响很好，我们对贵司的创新实力印象深刻。",
            "我行针对科技型中小企业推出了「科技贷」产品，最高5000万授信，纯信用无抵押，非常契合贵司当前的发展阶段。",
        ],
        "供应链管理": [
            f"您好，{info['name']}在电子元器件供应链领域深耕多年，服务客户超200家，业务发展非常好。",
            "我行可为贵司提供供应链金融整体解决方案，基于核心企业信用的应收账款融资，融资比例最高90%，T+1放款，能有效提升贵司的资金周转效率。",
        ],
        "SaaS服务": [
            f"您好，了解到{info['name']}刚完成A轮融资，月活用户突破10万，发展势头非常好！",
            "针对像贵司这样的高成长SaaS企业，我行有「小微企业快捷贷」产品，线上申请、快速审批，额度最高1000万，非常灵活。",
        ],
        "消费电子": [
            f"您好，{info['name']}作为全球知名的消费电子企业，2025年营收增长18%，成绩斐然。",
            "我行可围绕贵司的供应链、跨境业务和员工代发等场景，提供一站式综合金融服务方案，包括集团现金管理、跨境金融、并购贷款等。",
            "此外，我行此前已为贵司承销过中期票据，合作基础良好，希望能进一步深化战略合作。",
        ],
        "环保科技": [
            f"您好，了解到{info['name']}刚中标大兴区工业废水处理PPP项目，恭喜！我行在绿色金融领域有丰富的产品经验。",
            "我行「绿色金融专项贷」支持环保项目，利率较普通贷款下浮20-50BP，期限最长15年，可配套PPP项目的回款周期灵活安排还款计划。",
        ],
    }

    value_props_map = {
        "智能硬件": ["科技贷：最高5000万授信，纯信用无抵押", "设备融资租赁：首付低至10%"],
        "供应链管理": ["供应链金融：应收账款融资比例90%", "流动资金循环贷款：随借随还"],
        "SaaS服务": ["小微企业快捷贷：线上申请，3-5天放款", "科技贷：信用无抵押"],
        "消费电子": ["集团现金管理平台：多层级资金归集", "跨境金融：多币种结算+汇率避险", "并购贷款：最高60%融资比例"],
        "环保科技": ["绿色金融专项贷：利率下浮20-50BP", "PPP项目配套融资方案"],
    }

    return {
        "credit_code": cc,
        "company_name": name,
        "business_address": info["business_address"],
        "registered_address": info["registered_address"],
        "contact_channels": channels,
        "insights": {
            "company_profile": profile,
            "recent_news": news,
            "industry_analysis": analysis_map.get(industry, f"{industry}行业发展前景广阔。"),
        },
        "scripts": {
            "approach": approach_map.get(industry, f"以我行优势产品为切入点，为{name}提供定制化金融方案。"),
            "talking_points": talking_points_map.get(industry, [f"您好，了解到{name}发展良好，我行愿为贵司提供优质金融服务。"]),
            "value_props": value_props_map.get(industry, ["综合金融服务方案"]),
        },
    }


def update_customer_outreach():
    data = _load("customer_outreach.json")
    for cc, info in CUSTOMERS.items():
        data[cc] = _build_outreach_entry(cc, info)
    _save("customer_outreach.json", data)
    print("✓ customer_outreach.json — 5家企业拓户数据已添加")


# ============================================================
# 5. 生成 product_recommendations.json（recommendations 部分）
# ============================================================
def update_product_recommendations():
    data = _load("product_recommendations.json")
    recs = data.setdefault("recommendations", {})

    rec_templates = {
        "91110108MA01B3XK2PB": {
            "analysis_summary": "该企业为智能家居硬件领域的专精特新企业，年营收约1.2亿元，拥有12项发明专利。团队规模80余人，处于快速成长期，预计有设备采购和流动资金需求。",
            "recommendations": [
                {"key": "tech_loan", "priority": "high",
                 "reason": "作为专精特新企业，符合科技贷准入条件。年营收1.2亿且有稳定增长，信用风险可控。",
                 "expected_amount": "1000-3000万"},
                {"key": "equipment_lease", "priority": "high",
                 "reason": "智能硬件研发需要大量测试设备和生产线投入，设备融资租赁可有效缓解资金压力。",
                 "expected_amount": "500-1500万"},
                {"key": "working_capital", "priority": "medium",
                 "reason": "企业处于快速成长期，日常经营流动资金需求较大。",
                 "expected_amount": "500-1000万"},
                {"key": "structured_deposit", "priority": "low",
                 "reason": "企业有阶段性闲置资金，可通过结构性存款提升收益。",
                 "expected_amount": "200-500万"},
            ],
        },
        "91440300MA5DCTJH8NB": {
            "analysis_summary": "该企业为电子元器件供应链整合企业，年交易额约5亿元，服务客户超200家。资金周转需求大，上下游关系复杂，适合供应链金融和流动资金产品。",
            "recommendations": [
                {"key": "supply_chain_finance", "priority": "high",
                 "reason": "供应链管理企业，应收账款规模大。基于核心企业信用的保理融资可大幅提升资金周转效率。",
                 "expected_amount": "3000-5000万"},
                {"key": "working_capital", "priority": "high",
                 "reason": "高频资金周转需求，循环贷款可灵活匹配经营节奏。",
                 "expected_amount": "2000-5000万"},
                {"key": "bill_pool", "priority": "medium",
                 "reason": "供应链企业票据收付量大，票据池可统一管理、高效融资。",
                 "expected_amount": "—"},
                {"key": "cash_management", "priority": "low",
                 "reason": "若未来扩展集团化运营，现金管理平台可提升资金管控效率。",
                 "expected_amount": "—"},
            ],
        },
        "91440300MA5DCTJHQAZ": {
            "analysis_summary": "该企业为SaaS协同办公领域的创业公司，刚完成A轮融资，月活用户10万+。轻资产模式，传统抵押贷款不适用，信用类融资产品更匹配。",
            "recommendations": [
                {"key": "sme_quick_loan", "priority": "high",
                 "reason": "初创企业，额度需求适中。快捷贷线上申请、快速放款，契合创业公司效率要求。",
                 "expected_amount": "300-1000万"},
                {"key": "tech_loan", "priority": "medium",
                 "reason": "已获高新技术企业认定，符合科技贷准入。但成立时间较短，额度可能受限。",
                 "expected_amount": "500-1000万"},
                {"key": "structured_deposit", "priority": "low",
                 "reason": "A轮融资后账上有一定闲置资金，可通过结构性存款保值增值。",
                 "expected_amount": "200-500万"},
            ],
        },
        "91440300MA5DCTJHQA1": {
            "analysis_summary": "该企业为全球知名消费电子巨头，港股上市，年营收超3000亿元。金融需求多元化，涵盖供应链金融、跨境业务、现金管理、投行等多个维度。",
            "recommendations": [
                {"key": "cash_management", "priority": "high",
                 "reason": "全球3万+员工，分子公司众多，集团资金归集和实时管控需求突出。",
                 "expected_amount": "—"},
                {"key": "cross_border", "priority": "high",
                 "reason": "作为全球化企业，跨境结算、多币种管理和汇率避险需求巨大。",
                 "expected_amount": "—"},
                {"key": "supply_chain_finance", "priority": "medium",
                 "reason": "供应链上下游企业数量庞大，应收账款融资可优化供应链资金效率。",
                 "expected_amount": "5-10亿"},
                {"key": "ma_loan", "priority": "medium",
                 "reason": "消费电子行业并购频繁，并购贷款可支持战略扩张。",
                 "expected_amount": "10-30亿"},
                {"key": "ipo_service", "priority": "low",
                 "reason": "已在港股上市，后续如有A股或双重上市计划可提供上市辅导服务。",
                 "expected_amount": "—"},
            ],
        },
        "91440300MA5DCTJHQA2": {
            "analysis_summary": "该企业为环保科技新兴企业，刚中标政府PPP项目，处于起步阶段。绿色金融专项贷最为匹配，可结合PPP回款周期灵活设计。",
            "recommendations": [
                {"key": "green_finance", "priority": "high",
                 "reason": "环保企业，完全符合绿色金融准入。PPP项目有稳定回款预期，风险可控。",
                 "expected_amount": "500-2000万"},
                {"key": "equipment_lease", "priority": "high",
                 "reason": "废水处理设备采购金额大，设备融资租赁可降低前期资金压力。",
                 "expected_amount": "300-800万"},
                {"key": "sme_quick_loan", "priority": "medium",
                 "reason": "小微企业，日常经营流动资金需求。快捷贷流程简单，适合初创企业。",
                 "expected_amount": "100-500万"},
                {"key": "working_capital", "priority": "low",
                 "reason": "如业务持续增长，可升级为循环贷款以满足更大额度的流动资金需求。",
                 "expected_amount": "300-1000万"},
            ],
        },
    }

    for cc, template in rec_templates.items():
        recs[cc] = {
            "credit_code": cc,
            "company_name": CUSTOMERS[cc]["name"],
            "analysis_summary": template["analysis_summary"],
            "recommendations": template["recommendations"],
        }

    _save("product_recommendations.json", data)
    print("✓ product_recommendations.json — 5家企业产品推荐已添加")


# ============================================================
# 主流程
# ============================================================
if __name__ == "__main__":
    print("=" * 60)
    print("为上传客户生成完整测试数据")
    print("=" * 60)

    update_uploaded_scores()
    update_name_index()
    update_risk_check()
    update_customer_outreach()
    update_product_recommendations()

    print("=" * 60)
    print("全部完成！5家企业数据已补充至各数据文件")
    print("=" * 60)
