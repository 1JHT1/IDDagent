#!/usr/bin/env python3.13
# -*- coding: utf-8 -*-
"""
产品智能匹配 - 规则引擎需求提取测试
"""
import sys
sys.path.insert(0, str(__import__("pathlib").Path(__file__).parent.parent))

from skills.product_match import _extract_needs


def run_tests():
    tests = [
        # (描述, 输入文本, 预期字段)
        (
            "工程款+原材料+收益最高",
            "客户最近有一笔5千万的工程款打入，会停留一个月后，需要抽出4千万购买原材料，请推荐收益最高的产品",
            {"total_amount": 5000, "available_amount": 4000, "duration_days": 30,
             "purpose": "购买原材料", "return_priority": True},
        ),
        (
            "3000万闲置+3个月+保本",
            "客户有3000万闲置资金，想要保本稳健的短期理财，期限3个月左右",
            {"total_amount": 3000, "duration_days": 90, "risk_preference": "low"},
        ),
        (
            "1亿+灵活管理+随时",
            "公司有一笔1亿的融资到账，需要灵活管理，随时可能要用于扩张",
            {"total_amount": 10000, "liquidity_need": "high", "purpose": "扩张"},
        ),
        (
            "500万+大额存单+1年",
            "客户账上有500万，需要存个大额存单，1年期",
            {"total_amount": 500, "duration_days": 365},
        ),
        (
            "两千万闲置半年+保本",
            "两千万闲置半年，保本就行",
            {"total_amount": 2000, "duration_days": 180, "risk_preference": "low"},
        ),
        (
            "一个亿+三个月+稳健",
            "公司最近拿到一个亿的融资，三个月内暂时不用，想做稳健配置",
            {"total_amount": 10000, "duration_days": 90, "risk_preference": "low"},
        ),
        (
            "2000万应收账款保理",
            "供应商那边有2000万的应收账款，想做个保理融资",
            {"total_amount": 2000},
        ),
        (
            "500万设备采购",
            "公司有500万需要采购新设备，考虑融资租赁方案",
            {"total_amount": 500, "purpose": "采购"},
        ),
    ]

    passed = 0
    failed = 0

    for i, (desc, text, expected) in enumerate(tests, 1):
        result = _extract_needs(text)
        errors = []
        for key, exp_val in expected.items():
            actual = result.get(key)
            if actual != exp_val:
                errors.append(f"{key}: 预期={exp_val}, 实际={actual}")

        if errors:
            print(f"❌ T{i} [{desc}]")
            for e in errors:
                print(f"   {e}")
            failed += 1
        else:
            print(f"✅ T{i} [{desc}]")
            passed += 1

    total = passed + failed
    print(f"\n测试结果: {passed}/{total} 通过", end="")
    if failed == 0:
        print(" ✅ 全部通过！")
    else:
        print(f" ❌ {failed} 个失败")
    return failed == 0


if __name__ == "__main__":
    success = run_tests()
    sys.exit(0 if success else 1)
