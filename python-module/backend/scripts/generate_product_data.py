# -*- coding: utf-8 -*-
"""生成 product_recommendations.json — 产品智荐数据"""
import json
from pathlib import Path

DATA_DIR = Path(__file__).parent.parent / "data"

# ============================================================
# 产品池定义（银行金融产品）
# ============================================================
PRODUCT_POOL = {
    "tech_loan": {
        "product_name": "科技贷",
        "category": "对公信贷",
        "priority": "high",
        "features": [
            "最高授信5000万元",
            "纯信用无抵押，政府贴息后实际利率低至2.5%",
            "审批效率高，最快5个工作日放款",
            "可随借随还，按日计息",
        ],
        "application_period": "5-7个工作日",
        "target_profile": "高新技术企业、专精特新、科技型中小企业",
    },
    "equipment_lease": {
        "product_name": "设备融资租赁",
        "category": "融资租赁",
        "priority": "high",
        "features": [
            "首付低至设备价值的10%",
            "租赁期限最长8年",
            "租赁期满可选择留购、续租或退回",
            "可享受加速折旧税收优惠",
        ],
        "application_period": "10-15个工作日",
        "target_profile": "制造业企业、有设备购置需求的企业",
    },
    "supply_chain_finance": {
        "product_name": "供应链金融-应收账款融资",
        "category": "供应链金融",
        "priority": "medium",
        "features": [
            "基于核心企业信用的应收账款保理",
            "融资比例最高可达应收账款的90%",
            "在线审批，T+1放款",
            "可对接ERP系统实现自动化融资",
        ],
        "application_period": "1-3个工作日",
        "target_profile": "核心企业上游供应商、有稳定应收账款的企业",
    },
    "cross_border": {
        "product_name": "跨境金融服务方案",
        "category": "国际业务",
        "priority": "medium",
        "features": [
            "跨境人民币结算便利化",
            "多币种现金池管理",
            "进口信用证、出口信保融资",
            "汇率避险工具：远期锁汇、期权",
        ],
        "application_period": "3-5个工作日（额度启用）",
        "target_profile": "外贸企业、跨境投资企业、自贸区企业",
    },
    "green_finance": {
        "product_name": "绿色金融专项贷",
        "category": "对公信贷",
        "priority": "medium",
        "features": [
            "支持节能环保、清洁能源、碳减排项目",
            "利率较普通贷款下浮20-50BP",
            "贷款期限最长15年，宽限期最长3年",
            "可配套人行碳减排支持工具再贷款",
        ],
        "application_period": "10-15个工作日",
        "target_profile": "新能源、环保、节能减排企业",
    },
    "working_capital": {
        "product_name": "流动资金循环贷款",
        "category": "对公信贷",
        "priority": "medium",
        "features": [
            "一次授信，循环使用",
            "额度最高1亿元",
            "可随借随还，按实际使用天数计息",
            "支持线上自助提款还款",
        ],
        "application_period": "7-10个工作日",
        "target_profile": "有持续性流动资金需求的企业",
    },
    "bond_underwriting": {
        "product_name": "债务融资工具承销",
        "category": "投资银行",
        "priority": "high",
        "features": [
            "超短期融资券、中期票据、定向工具等",
            "注册制发行，一次注册分次发行",
            "融资成本低于同期银行贷款100-200BP",
            "提升企业资本市场知名度",
        ],
        "application_period": "30-60个工作日（含注册）",
        "target_profile": "AA级及以上评级、年营收超5亿元的企业",
    },
    "structured_deposit": {
        "product_name": "结构性存款",
        "category": "对公理财",
        "priority": "low",
        "features": [
            "保本浮动收益型",
            "期限灵活：1个月至1年",
            "年化收益率1.5%-3.5%",
            "可开具存款证实书，满足保证金需求",
        ],
        "application_period": "T+1起息",
        "target_profile": "有闲置资金配置需求的企业",
    },
    "sme_quick_loan": {
        "product_name": "小微企业快捷贷",
        "category": "对公信贷",
        "priority": "medium",
        "features": [
            "额度最高1000万元",
            "审批流程简化，快速放款",
            "支持线上申请、线上签约",
            "担保方式灵活，可信用、保证、抵押",
        ],
        "application_period": "3-5个工作日",
        "target_profile": "小微企业、初创企业",
    },
    "ma_loan": {
        "product_name": "并购贷款",
        "category": "投资银行",
        "priority": "medium",
        "features": [
            "融资比例最高可达并购交易价款的60%",
            "贷款期限最长7年",
            "可配套股权融资顾问服务",
            "支持境内及跨境并购交易",
        ],
        "application_period": "30-45个工作日",
        "target_profile": "有并购扩张需求、现金流稳定的企业",
    },
    "cash_management": {
        "product_name": "集团现金管理平台",
        "category": "现金管理",
        "priority": "low",
        "features": [
            "多层级资金归集与下拨",
            "实时余额查询与支付管控",
            "内部资金计价与虚拟账户",
            "银企直连，无缝对接ERP",
        ],
        "application_period": "15-30个工作日（系统对接）",
        "target_profile": "集团型企业、分子公司较多的企业",
    },
    "ipo_service": {
        "product_name": "上市辅导综合服务",
        "category": "投资银行",
        "priority": "medium",
        "features": [
            "IPO前期辅导与财务规范",
            "股权激励方案设计",
            "引入战略投资者对接",
            "募集资金监管与现金管理",
        ],
        "application_period": "根据上市进展安排",
        "target_profile": "Pre-IPO阶段企业、拟上市企业",
    },
    "bill_pool": {
        "product_name": "票据池",
        "category": "票据业务",
        "priority": "low",
        "features": [
            "票据统一入池管理",
            "支持银承和商承质押融资",
            "票据到期自动托收",
            "实时查询票据状态",
        ],
        "application_period": "5-7个工作日（签约）",
        "target_profile": "票据收付量大、有票据管理需求的企业",
    },
}

# ============================================================
# 15家企业产品推荐配置
# ============================================================
RECOMMENDATIONS = {
    "91110108MA01B3XK2P": {
        "credit_code": "91110108MA01B3XK2P",
        "company_name": "北京星河科技有限公司",
        "analysis_summary": "该企业为人工智能大模型赛道头部企业，已完成B轮融资，中标智慧政务大单，团队扩张迅速。现有结算日均存款800万+，预计年内有较大的信贷及综合金融需求。",
        "recommendations": [
            {"key": "tech_loan", "priority": "high", "reason": "作为高成长科技企业，融资需求迫切。已完成B轮1.5亿融资，市场认可度高，信用风险可控。推荐匹配最高额度科技贷5000万。", "expected_amount": "3000-5000万"},
            {"key": "bond_underwriting", "priority": "high", "reason": "年营收2.8亿且高速增长，可启动信用评级。通过发行中期票据替换部分银行借款可显著降低财务成本。", "expected_amount": "3-5亿"},
            {"key": "cash_management", "priority": "medium", "reason": "日均结算存款800万+，团队扩张至120+人，需统一资金管理平台提升效率。", "expected_amount": "—"},
            {"key": "structured_deposit", "priority": "low", "reason": "B轮融资后账上现金充裕，可通过结构性存款提升闲置资金收益。", "expected_amount": "1000-3000万"},
        ],
    },
    "91440300MA5DCTJH8N": {
        "credit_code": "91440300MA5DCTJH8N",
        "company_name": "深圳前海创新金融集团有限公司",
        "analysis_summary": "前海自贸区重点金融控股平台，管理资产超300亿，净利润增长35%。获批跨境理财通试点，旗下保理公司已与我行有合作基础。",
        "recommendations": [
            {"key": "bond_underwriting", "priority": "high", "reason": "管理资产规模超300亿，具备AA级以上评级条件。发行中期票据和超短融可优化整体债务结构。", "expected_amount": "20-50亿"},
            {"key": "cross_border", "priority": "high", "reason": "刚获批跨境理财通试点，跨境资金池和多币种结算需求将大幅增长。我行大湾区网点布局是核心优势。", "expected_amount": "—"},
            {"key": "cash_management", "priority": "medium", "reason": "下辖四大业务板块，分子公司众多，集团资金归集和实时管控需求突出。", "expected_amount": "—"},
            {"key": "ma_loan", "priority": "medium", "reason": "作为投资控股平台，并购扩张需求持续。已参与半导体Pre-IPO轮投资，后续并购融资需求可期。", "expected_amount": "5-10亿"},
            {"key": "structured_deposit", "priority": "low", "reason": "集团各板块资金沉淀量较大，可通过结构性存款实现分层配置。", "expected_amount": "5000万-2亿"},
        ],
    },
    "91330108MA27XK5B3D": {
        "credit_code": "91330108MA27XK5B3D",
        "company_name": "杭州云栖大数据技术有限公司",
        "analysis_summary": "阿里云生态核心合作伙伴，专注数据治理平台。DataOps3.0平台首发签约12家客户，数字经济政策利好叠加，业绩增长确定性高。",
        "recommendations": [
            {"key": "tech_loan", "priority": "high", "reason": "数字经济重点企业，产品技术壁垒高。科技贷可支持平台研发投入和客户推广，政府贴息可进一步降低融资成本。", "expected_amount": "2000-3000万"},
            {"key": "supply_chain_finance", "priority": "medium", "reason": "作为阿里云生态核心伙伴，可基于阿里云生态数据提供信用融资，盘活对云服务商的应收账款。", "expected_amount": "500-1000万"},
            {"key": "cash_management", "priority": "low", "reason": "企业SaaS订阅制收入模式带来稳定的现金流，适合配置银企直连提升资金管理效率。", "expected_amount": "—"},
        ],
    },
    "91310115MA1K3L6M9Q": {
        "credit_code": "91310115MA1K3L6M9Q",
        "company_name": "上海浦江智能装备制造有限公司",
        "analysis_summary": "智能装备领域高新技术企业，中标比亚迪6800万产线大单，机器人产品通过UL认证进军海外。设备采购和产能扩张需求迫切。",
        "recommendations": [
            {"key": "equipment_lease", "priority": "high", "reason": "为交付比亚迪大单需采购大量精密加工设备，设备融资租赁首付低至10%，可大幅减轻现金流压力。", "expected_amount": "5000-8000万"},
            {"key": "tech_loan", "priority": "high", "reason": "智能制造企业，市级示范工厂，可享受科创金融专项利率优惠。用于产能扩建的流动资金补充。", "expected_amount": "3000-5000万"},
            {"key": "cross_border", "priority": "medium", "reason": "焊接机器人通过UL认证后进军海外，需配套出口信保融资和汇率避险服务。", "expected_amount": "—"},
            {"key": "supply_chain_finance", "priority": "low", "reason": "作为上汽、比亚迪的供应商，可基于核心企业信用获得应收账款融资。", "expected_amount": "1000-2000万"},
        ],
    },
    "91440101MA5AK7T4R2": {
        "credit_code": "91440101MA5AK7T4R2",
        "company_name": "广州珠江国际贸易有限公司",
        "analysis_summary": "年贸易额超15亿的大宗商品和跨境电商龙头，与广州港共建物流枢纽。RCEP深化和南沙自贸区政策带来新增量。",
        "recommendations": [
            {"key": "cross_border", "priority": "high", "reason": "外贸核心企业，年贸易额15亿+覆盖30国。需进口信用证、跨境人民币结算、出口信保融资等一揽子贸易金融服务。", "expected_amount": "—"},
            {"key": "supply_chain_finance", "priority": "high", "reason": "与广物控股等大客户供应链关系稳定，可通过应收账款保理和预付款融资优化上下游资金流。", "expected_amount": "5000万-1亿"},
            {"key": "working_capital", "priority": "medium", "reason": "大宗商品贸易资金周转量大，循环贷款可随借随还，匹配贸易周期性资金需求。", "expected_amount": "5000万-1亿"},
            {"key": "bill_pool", "priority": "low", "reason": "贸易业务结算中票据量大，票据池可统一管理银承、商承质押融资，提升票据使用效率。", "expected_amount": "—"},
        ],
    },
    "91510100MA6C8M2F5W": {
        "credit_code": "91510100MA6C8M2F5W",
        "company_name": "成都天府半导体材料有限公司",
        "analysis_summary": "第三代半导体SiC衬底龙头，获大基金二期投资1亿，SiC良率突破80%达国际水平。与意法半导体签长期供货协议，产能扩建需求迫切。",
        "recommendations": [
            {"key": "tech_loan", "priority": "high", "reason": "半导体国产替代核心企业，已获大基金背书。需大量资金投入产能扩建和研发，推荐半导体专项信贷最高额度。", "expected_amount": "3-5亿"},
            {"key": "equipment_lease", "priority": "high", "reason": "SiC晶圆产线设备投资大，融资租赁可分期支付设备款，匹配产能爬坡节奏。", "expected_amount": "2-3亿"},
            {"key": "ipo_service", "priority": "medium", "reason": "大基金二期投资1亿，预计未来2-3年启动IPO。可提前对接上市辅导和股权激励设计服务。", "expected_amount": "—"},
            {"key": "green_finance", "priority": "medium", "reason": "第三代半导体能效优于传统硅器件，碳化硅器件可助力新能源汽车减排，符合绿色金融支持方向。", "expected_amount": "5000万-1亿"},
        ],
    },
    "91420100MA4KX2L8H1": {
        "credit_code": "91420100MA4KX2L8H1",
        "company_name": "武汉光谷生物医药科技有限公司",
        "analysis_summary": "创新药企业，抗PD-L1单抗获批II期临床，获高瓴资本Pre-IPO轮2亿注资。二期厂房落成，年产能提升至万升级。",
        "recommendations": [
            {"key": "tech_loan", "priority": "high", "reason": "生物医药研发投入大、周期长，科技贷可覆盖临床II/III期费用。高瓴背书增加信用可靠性。", "expected_amount": "1-2亿"},
            {"key": "ipo_service", "priority": "high", "reason": "高瓴Pre-IPO轮注资2亿，预计1-2年内启动上市。可提前提供上市辅导和ESOP股权激励方案设计。", "expected_amount": "—"},
            {"key": "equipment_lease", "priority": "medium", "reason": "二期GMP厂房落成，后续设备采购仍有较大需求，融资租赁可匹配收入周期。", "expected_amount": "3000-5000万"},
            {"key": "structured_deposit", "priority": "low", "reason": "Pre-IPO融资后账上有较大资金沉淀，结构性存款可提升资金收益。", "expected_amount": "5000万-1亿"},
        ],
    },
    "91320115MA1W7J9P3B": {
        "credit_code": "91320115MA1W7J9P3B",
        "company_name": "南京江宁新能源动力有限公司",
        "analysis_summary": "新能源商用车动力电池pack企业，中标徐工年度3亿集中采购，获省级专精特新认定。产能扩张需求明确。",
        "recommendations": [
            {"key": "equipment_lease", "priority": "high", "reason": "为满足徐工年供3亿的订单规模，电池pack产线需大幅扩产，设备融资租赁可分期投入。", "expected_amount": "3000-5000万"},
            {"key": "green_finance", "priority": "high", "reason": "新能源动力电池属于绿色产业，可享受碳减排支持工具的低利率政策红利。", "expected_amount": "2000-3000万"},
            {"key": "supply_chain_finance", "priority": "medium", "reason": "作为徐工、三一等头部车企供应商，应收账款质量优良，可开展应收账款融资。", "expected_amount": "1000-3000万"},
        ],
    },
    "91500000MA5YU6RA4C": {
        "credit_code": "91500000MA5YU6RA4C",
        "company_name": "重庆两江新区供应链管理有限公司",
        "analysis_summary": "运营长安汽车供应商金融平台，覆盖500+供应商，交易额突破50亿。已与多家银行签约，获批自贸区创新试点。",
        "recommendations": [
            {"key": "supply_chain_finance", "priority": "high", "reason": "供应链金融平台核心业务，我行可作为资金方接入平台，为长安汽车上游500+供应商提供融资。首期授信10亿，支持API直连。", "expected_amount": "10亿（平台授信总额度）"},
            {"key": "cross_border", "priority": "high", "reason": "刚获批重庆自贸区跨境供应链金融创新试点，跨境保理和FT账户服务需求迫切。", "expected_amount": "—"},
            {"key": "cash_management", "priority": "medium", "reason": "平台日均资金流量大，需银企直连和自动化资金清分系统提升效率。", "expected_amount": "—"},
            {"key": "bill_pool", "priority": "low", "reason": "供应商票据结算量大，票据池可统一管理质押融资，为平台增厚服务收入。", "expected_amount": "—"},
        ],
    },
    "91320594MA1R8T2B6E": {
        "credit_code": "91320594MA1R8T2B6E",
        "company_name": "苏州工业园区芯片设计有限公司",
        "analysis_summary": "模拟芯片设计企业，BMS芯片通过车规认证进入比亚迪供应链。获大基金三期投资5000万。",
        "recommendations": [
            {"key": "tech_loan", "priority": "high", "reason": "车规芯片研发流片费用高，已获大基金背书。科技贷可支持后续产品线拓展和产能扩张。", "expected_amount": "2000-5000万"},
            {"key": "equipment_lease", "priority": "medium", "reason": "芯片测试验证设备投资大，融资租赁可分期投入，缓解流片期间的现金流压力。", "expected_amount": "1000-3000万"},
            {"key": "sme_quick_loan", "priority": "low", "reason": "作为快速成长的IC设计企业，快捷贷可满足部分流动性管理需求。", "expected_amount": "500-1000万"},
        ],
    },
    "91610131MA6U2F8G8K": {
        "credit_code": "91610131MA6U2F8G8K",
        "company_name": "西安高新区航空航天零部件有限公司",
        "analysis_summary": "中航西飞核心供应商，中标C919国产化替代项目。持有军品质量体系认证，订单稳定。",
        "recommendations": [
            {"key": "equipment_lease", "priority": "high", "reason": "为承接C919项目需采购高端五轴加工中心和复合材料成型设备，融资租赁可分期投入。", "expected_amount": "3000-5000万"},
            {"key": "tech_loan", "priority": "high", "reason": "航空精密制造领域专精特新标杆，军工产业链企业可享专项信贷利率优惠。", "expected_amount": "2000-5000万"},
            {"key": "working_capital", "priority": "medium", "reason": "军品订单周期长但回款稳定，循环贷款可解决备料和生产期间的流动性需求。", "expected_amount": "1000-2000万"},
        ],
    },
    "91410100MA44N6K3D9": {
        "credit_code": "91410100MA44N6K3D9",
        "company_name": "郑州航空港区跨境电子商务有限公司",
        "analysis_summary": "依托郑州航空港的跨境电商物流企业，年处理包裹2000万+。与菜鸟网络共建中东欧海外仓。",
        "recommendations": [
            {"key": "cross_border", "priority": "high", "reason": "跨境电商物流核心企业，海外仓建设和跨境运费结算需求大。我行跨境人民币和运费保理可显著优化资金周转。", "expected_amount": "—"},
            {"key": "equipment_lease", "priority": "medium", "reason": "中东欧海外仓和郑州自动化分拣设备投资较大，融资租赁可分期投入。", "expected_amount": "3000-5000万"},
            {"key": "sme_quick_loan", "priority": "medium", "reason": "跨境电商回款周期较长，快捷贷可补充日常运营流动资金。", "expected_amount": "500-1000万"},
            {"key": "working_capital", "priority": "low", "reason": "大促期间（双11/618）物流峰值需提前备货和人员，循环贷款可满足季节性资金需求。", "expected_amount": "1000-2000万"},
        ],
    },
    "91120116MA05K8N3X7": {
        "credit_code": "91120116MA05K8N3X7",
        "company_name": "天津滨海新区精工制造有限公司",
        "analysis_summary": "中船重工精密零件供应商，中标年度集中采购项目。高端制造领域深耕多年。",
        "recommendations": [
            {"key": "equipment_lease", "priority": "high", "reason": "精密加工设备单价高、更新换代快，融资租赁可保持设备先进性同时优化现金流。", "expected_amount": "2000-4000万"},
            {"key": "working_capital", "priority": "medium", "reason": "船舶制造周期长，流动资金需求大。循环贷款可匹配订单周期灵活使用。", "expected_amount": "1000-2000万"},
            {"key": "sme_quick_loan", "priority": "low", "reason": "日常经营周转补充，额度适中、手续简便。", "expected_amount": "500-800万"},
        ],
    },
    "91460000MA5T8F3K6D": {
        "credit_code": "91460000MA5T8F3K6D",
        "company_name": "海南自贸港国际物流有限公司",
        "analysis_summary": "海南自贸港国际物流企业，获批加工增值免关税试点。封关运作提速带来政策红利。",
        "recommendations": [
            {"key": "cross_border", "priority": "high", "reason": "自贸港核心企业，加工增值免关税试点的跨境结算和FT账户需求突出。我行可提供自贸港专属跨境金融方案。", "expected_amount": "—"},
            {"key": "equipment_lease", "priority": "medium", "reason": "保税仓储和物流设施建设投入大，融资租赁可缓解前期资本支出压力。", "expected_amount": "2000-3000万"},
            {"key": "working_capital", "priority": "low", "reason": "物流运营日常流动性管理，循环贷款灵活匹配业务节奏。", "expected_amount": "500-1000万"},
        ],
    },
    "91350200MA34P9J8K2": {
        "credit_code": "91350200MA34P9J8K2",
        "company_name": "厦门两岸科技孵化器有限公司",
        "analysis_summary": "国家级A类优秀科技孵化器，运营面积5万+㎡，在孵企业120+家。孵化器平台价值日益凸显。",
        "recommendations": [
            {"key": "tech_loan", "priority": "high", "reason": "孵化器运营主体可申请经营性物业贷，同时可为120+在孵企业批量推荐科技贷产品。", "expected_amount": "2000-5000万"},
            {"key": "sme_quick_loan", "priority": "medium", "reason": "可为在孵企业提供批量融资方案，小微企业快捷贷标准化流程适合批量推荐。", "expected_amount": "单户500-1000万"},
            {"key": "cash_management", "priority": "low", "reason": "孵化器收取的租金和服务费用管理，银企直连可提升效率。", "expected_amount": "—"},
        ],
    },
}

# 写入文件
with open(DATA_DIR / "product_recommendations.json", "w", encoding="utf-8") as f:
    json.dump({"products": PRODUCT_POOL, "recommendations": RECOMMENDATIONS}, f, ensure_ascii=False, indent=2)

# 统计
total = 0
for code, data in RECOMMENDATIONS.items():
    recs = data["recommendations"]
    high = sum(1 for r in recs if r["priority"] == "high")
    med = sum(1 for r in recs if r["priority"] == "medium")
    low = sum(1 for r in recs if r["priority"] == "low")
    total += len(recs)
    print(f"  {data['company_name']}: {len(recs)}个(高{high}/中{med}/低{low})")

print(f"\n✅ 生成 product_recommendations.json: {len(RECOMMENDATIONS)} 家企业, {total} 条推荐")
print(f"✅ 产品池: {len(PRODUCT_POOL)} 款产品")
