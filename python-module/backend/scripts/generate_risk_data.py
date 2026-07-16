# -*- coding: utf-8 -*-
"""生成 risk_check.json 和 company_name_index.json"""
import json
from pathlib import Path

DATA_DIR = Path(__file__).parent.parent / "data"

# ============================================================
# 12家来自潜客清单的企业 + 3家额外企业
# ============================================================
COMPANIES = [
    # (credit_code, company_name, risk_level, risk_summary)
    ("91110108MA01B3XK2P", "北京星河科技有限公司", "high",
     "该企业存在营业执照期限异常、法人变更频繁、注册地址与经营地址不一致等多项高风险特征，建议谨慎办理开户业务。"),
    ("91440300MA5DCTJH8N", "深圳前海创新金融集团有限公司", "high",
     "该企业涉及跨境金融业务，受益人穿透为境外壳公司，且命中国际制裁名单，风险等级为高，建议拒绝受理。"),
    ("91330108MA27XK5B3D", "杭州云栖大数据技术有限公司", "low",
     "该企业经营状况良好，各项信息合规，未发现明显风险点，可正常办理开户业务。"),
    ("91310115MA1K3L6M9Q", "上海浦江智能装备制造有限公司", "low",
     "该企业为高新技术制造企业，信息完整合规，未发现风险点，可正常办理开户。"),
    ("91440101MA5AK7T4R2", "广州珠江国际贸易有限公司", "medium",
     "该企业整体风险可控，但存在受益所有人识别不清晰及小额行政处罚记录，建议加强尽职调查后办理。"),
    ("91510100MA6C8M2F5W", "成都天府半导体材料有限公司", "low",
     "该企业各项信息正常，未发现明显风险点，可正常办理开户业务。"),
    ("91420100MA4KX2L8H1", "武汉光谷生物医药科技有限公司", "medium",
     "该企业经营状态正常，但近1年有1次行政处罚记录及法人变更，建议尽职调查后办理。"),
    ("91320115MA1W7J9P3B", "南京江宁新能源动力有限公司", "low",
     "该企业信息完整合规，经营状况稳定，未发现明显风险点。"),
    ("91500000MA5YU6RA4C", "重庆两江新区供应链管理有限公司", "high",
     "该企业关联多起诉讼案件，法人被列为失信被执行人，且经营范围涉及高风险行业，建议暂缓受理。"),
    ("91320594MA1R8T2B6E", "苏州工业园区芯片设计有限公司", "low",
     "该企业为政府扶持的高新技术企业，资质齐全，经营规范，未发现风险点。"),
    ("91610131MA6U2F8G8K", "西安高新区航空航天零部件有限公司", "low",
     "该企业为军工配套供应商，信息完整，资质合规，未发现风险点。"),
    ("91410100MA44N6K3D9", "郑州航空港区跨境电子商务有限公司", "medium",
     "该企业涉及跨境电商业务，近1年跨境交易金额较大，建议关注资金来源合规性。"),
    # 额外3家（不在潜客清单中）
    ("91120116MA05K8N3X7", "天津滨海新区精工制造有限公司", "high",
     "该企业涉及多起知识产权侵权诉讼，法人变更频繁，营业执照即将到期，风险等级为高。"),
    ("91460000MA5T8F3K6D", "海南自贸港国际物流有限公司", "medium",
     "该企业为新注册自贸港企业，受益所有人信息不完整，建议完善信息后受理。"),
    ("91350200MA34P9J8K2", "厦门两岸科技孵化器有限公司", "low",
     "该企业经营规范，资质齐全，为国家级科技孵化器运营主体，未发现风险点。"),
]


def make_business_items(level):
    """生成工商信息10项明细"""
    items = [
        ("企业基本信息", "正常", False, "企业基本信息完整有效"),
        ("营业执照期限异常", "正常", False, "营业执照长期有效"),
        ("注册地址与经营地址不一致", "正常", False, "注册地址与经营地址一致"),
        ("经营范围变更", "正常", False, "近2年未发生经营范围重大变更"),
        ("法人代表信息", "正常", False, "法人代表任职稳定"),
        ("股东及出资信息", "正常", False, "股东结构清晰"),
        ("主要人员变更", "正常", False, "高管团队稳定"),
        ("经营异常名录", "正常", False, "无经营异常记录"),
        ("行政处罚信息", "正常", False, "无行政处罚记录"),
        ("分支机构情况", "正常", False, "无分支机构"),
    ]
    if level == "high":
        items[1] = ("营业执照期限异常", "异常", True, "营业执照有效期不足30日")
        items[2] = ("注册地址与经营地址不一致", "异常", True, "注册地址与实际经营地址不符")
        items[4] = ("法人代表信息", "异常", True, "法人3年内变更3次")
        items[5] = ("股东及出资信息", "关注", True, "股东为失信被执行人关联方")
        items[6] = ("主要人员变更", "异常", True, "主要人员频繁变更")
        items[7] = ("经营异常名录", "有记录", True, "曾被列入经营异常名录")
        items[8] = ("行政处罚信息", "关注", True, "有行政处罚记录")
    elif level == "medium":
        items[1] = ("营业执照期限异常", "关注", True, "营业执照将在6个月内到期")
        items[6] = ("主要人员变更", "关注", True, "近1年有人员变更记录")
        items[8] = ("行政处罚信息", "有记录", True, "有1次行政处罚记录，已整改")
    return items


def make_aml_items(level):
    """生成反洗钱8项明细"""
    items = [
        ("客户身份识别", "正常", False, "客户身份信息完整有效"),
        ("受益所有人识别", "正常", False, "受益所有人清晰可追溯"),
        ("交易对手筛查", "正常", False, "关联公司无异常记录"),
        ("制裁名单筛查", "正常", False, "未命中制裁名单"),
        ("负面舆情信息", "正常", False, "无负面舆情"),
        ("可疑交易报送", "正常", False, "无可疑交易记录"),
        ("跨境交易审查", "正常", False, "无高风险跨境交易"),
        ("客户洗钱风险评估", "低风险", False, "综合评分低于30分"),
    ]
    if level == "high":
        items[0] = ("客户身份识别", "关注", True, "受益所有人信息不完整")
        items[1] = ("受益所有人识别", "异常", True, "受益人穿透后为境外公司")
        items[2] = ("交易对手筛查", "关注", True, "关联公司涉及高风险行业")
        items[3] = ("制裁名单筛查", "命中", True, "命中制裁名单")
        items[4] = ("负面舆情信息", "关注", True, "有媒体负面报道")
        items[5] = ("可疑交易报送", "有记录", True, "关联账户有可疑交易记录")
        items[6] = ("跨境交易审查", "关注", True, "涉及高风险国家跨境交易")
        items[7] = ("客户洗钱风险评估", "高风险", False, "综合评分高于70分")
    elif level == "medium":
        items[1] = ("受益所有人识别", "关注", True, "受益链中有境外层")
        items[6] = ("跨境交易审查", "关注", True, "有大额跨境交易")
        items[7] = ("客户洗钱风险评估", "中等风险", False, "综合评分在30-70分之间")
    return items


def make_risk_items(level):
    """生成风险等级10项明细"""
    level_text = {"high": "高", "medium": "中", "low": "低"}
    score_map = {"high": "72", "medium": "45", "low": "15"}
    items = [
        ("综合风险等级", level_text[level], level == "high",
         f"基于28项指标综合评分，风险等级为：{level_text[level]}"),
        ("工商风险评分", f"{score_map[level]}/100", level == "high",
         f"工商信息维度得分{score_map[level]}分"),
        ("反洗钱风险评分", f"{score_map[level]}/100", level == "high",
         f"反洗钱维度得分{score_map[level]}分"),
        ("信用风险评分", f"{score_map[level]}/100", level == "high",
         f"信用维度得分{score_map[level]}分"),
        ("经营稳定性评估", "稳定" if level == "low" else "关注", level == "high",
         "企业经营状况总体稳定" if level == "low" else "企业经营存在不稳定因素"),
        ("开户受理建议", {"high": "建议拒绝", "medium": "可以受理（加强尽调）", "low": "建议受理"}[level],
         level == "high",
         {"high": "综合风险等级为高，建议拒绝开户", "medium": "综合风险中等，需加强尽职调查", "low": "综合风险低，建议受理开户"}[level]),
        ("尽职调查要求", {"high": "增强型", "medium": "标准型", "low": "简化型"}[level],
         level == "high", "根据风险等级确定尽职调查类型"),
        ("复核审批要求", {"high": "需要区域内控审批", "medium": "网点自行审批", "low": "网点自行审批"}[level],
         level == "high", "根据风险等级确定审批层级"),
        ("开户后监控要求", {"high": "持续监控", "medium": "定期监控", "low": "例行监控"}[level],
         level == "high", "根据风险等级确定监控频次"),
        ("关联风险提示", {"high": "存在", "medium": "无", "low": "无"}[level],
         level == "high", "关联企业存在风险记录" if level == "high" else "未发现关联风险"),
    ]
    return items


# ============================================================
# 生成 risk_check.json
# ============================================================
risk_data = {}
for code, name, level, summary in COMPANIES:
    risk_data[code] = {
        "credit_code": code,
        "company_name": name,
        "has_risk": level != "low",
        "risk_level": level,
        "risk_summary": summary,
        "details": {
            "business_info": {
                "name": "工商信息",
                "items": [{"name": n, "result": r, "has_risk": h, "detail": d}
                           for n, r, h, d in make_business_items(level)],
            },
            "aml": {
                "name": "反洗钱",
                "items": [{"name": n, "result": r, "has_risk": h, "detail": d}
                           for n, r, h, d in make_aml_items(level)],
            },
            "risk_level": {
                "name": "风险等级",
                "items": [{"name": n, "result": r, "has_risk": h, "detail": d}
                           for n, r, h, d in make_risk_items(level)],
            },
        },
    }

with open(DATA_DIR / "risk_check.json", "w", encoding="utf-8") as f:
    json.dump(risk_data, f, ensure_ascii=False, indent=2)
print(f"生成 risk_check.json: {len(risk_data)} 家企业")

# ============================================================
# 生成 company_name_index.json（双向索引）
# ============================================================
name_index = {}
for code, name, _level, _summary in COMPANIES:
    name_index[code] = name

with open(DATA_DIR / "company_name_index.json", "w", encoding="utf-8") as f:
    json.dump(name_index, f, ensure_ascii=False, indent=2)
print(f"生成 company_name_index.json: {len(name_index)} 条记录")

# 验证明细数量
for code, data in risk_data.items():
    total = sum(len(v["items"]) for v in data["details"].values())
    print(f"  {data['company_name']}: {data['risk_level']} - {total}项明细")
