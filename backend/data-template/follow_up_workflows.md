# 企业客户营销标准追问流程

## 概述

本文档定义了银行对公客户经理在与智能助手交互时的标准业务流程。助手在完成当前问题应答后，应参考此文档预测用户下一步最可能的需求，主动追问以引导流程。

## 核心业务场景与追问链路

### 场景 A：潜客识别 → 风险预查 → 拓户准备 → 产品推荐

用户先看潜客清单，然后查具体企业风险，再准备营销材料，最后推荐产品。

```
潜客清单（recommend_corporate_customers）
  └─ 追问：是否需要查看某个来源的客户详情？
      └─ 客户详情（recommend_corporate_customers detail）
          └─ 追问：是否需要查询该企业的开户风险？
              └─ 风险预查（check_company_risk）
                  └─ 追问：是否需要为该公司准备拓户营销材料？
                      └─ 拓户准备（prepare_customer_outreach）
                          └─ 追问：是否需要为该公司推荐合适的金融产品？
                              └─ 产品智荐（recommend_products）
                                  └─ 结束（或追问拓户准备）
```

### 场景 B：直接风险预查 → 拓户准备 → 产品推荐

用户直接查询某企业风险，后续跟进拓户和产品。

```
风险预查（check_company_risk）
  └─ 追问：是否需要为「{企业名}」准备拓户营销材料？
      └─ 拓户准备（prepare_customer_outreach）
          └─ 追问：是否需要为「{企业名}」推荐适合的金融产品？
              └─ 产品智荐（recommend_products）
                  └─ 追问：是否需要为「{企业名}」准备拓户营销材料？
```

### 场景 C：直接产品推荐 → 拓户准备

用户直接询问产品推荐，后续可能跟进拓户。

```
产品智荐（recommend_products）
  └─ 追问：是否需要为「{企业名}」准备拓户营销材料？
      └─ 拓户准备（prepare_customer_outreach）
          └─ 追问：是否需要为「{企业名}」推荐适合的金融产品？
```

### 场景 D：直接拓户准备 → 产品推荐 / 风险预查

```
拓户准备（prepare_customer_outreach）
  └─ 追问：是否需要为「{企业名}」推荐适合的金融产品？
      或 追问：是否需要查询「{企业名}」的开户风险？
```

## 追问生成规则

1. **追问必须自然、口语化**，以"是否需要"开头，例如"是否需要为「北京星河科技有限公司」推荐适合的金融产品？"
2. **追问必须包含当前企业名称**（如果有），使用中文书名号「」包裹
3. **追问针对当前企业主体**，不要跨企业追问
4. **单次只追问一个下一步动作**，不要列出多个选项
5. **优先追问产品推荐或拓户准备**，这两者是银行客户经理最核心的需求
6. **追问优先级**（从高到低）：
   - 产品推荐（recommend_products）—— 变现最后一公里
   - 拓户准备（prepare_customer_outreach）—— 营销必备
   - 风险预查（check_company_risk）—— 风控刚需
7. **不要追问当前已完成的技能**，避免循环
8. **如果用户已在当前会话中完成了所有核心流程**（风险+拓户+产品），则不要追问

## 严格反循环规则（优先级最高，必须遵守）

当判断是否应该追问时，必须先执行以下检查。如果命中任一条件，立即输出 `{"suggestion": null}`：

| 条件 | 说明 |
|------|------|
| 刚完成的技能是 `recommend_products` 且已调用过 `prepare_customer_outreach` | 产品完成后若拓户也做过，该企业流程闭环 → 不追问 |
| 刚完成的技能是 `prepare_customer_outreach` 且已调用过 `recommend_products` | 拓户完成后若产品也做过，该企业流程闭环 → 不追问 |
| 刚完成的技能是 `recommend_products`，且候选追问也是 `recommend_products` | 严禁追问自己 → 不追问 |
| 已调用技能列表中同时包含 `check_company_risk`、`prepare_customer_outreach`、`recommend_products` | 三大核心技能全部完成 → 不追问 |
| 刚完成的技能是 `recommend_corporate_customers` 且参数包含 `source_id` | 客户详情后向上追问风险 |
| 刚完成的技能是 `recommend_corporate_customers` 且参数不含 `source_id` | 潜客清单后向上追问查看详情 |

**追问走向速查表**（当前技能 → 唯一允许的追问方向）：

| 当前技能 | 允许的追问 | 禁止的追问 |
|----------|-----------|------------|
| `check_company_risk` (result) | `prepare_customer_outreach` | `recommend_products`、`check_company_risk` |
| `prepare_customer_outreach` (result) | `recommend_products` | `check_company_risk`、`prepare_customer_outreach` |
| `recommend_products` (result) | `prepare_customer_outreach`（仅当拓户未做过） | `recommend_products`、`check_company_risk` |
| `recommend_corporate_customers` (summary) | 追问查看详情 | — |
| `recommend_corporate_customers` (detail) | `check_company_risk` | — |

## 主体切换规则（当用户从企业A切换到企业B时）

1. **识别主体切换**：比较当前 `company_name` / `credit_code` 与"已调用技能"列表中上一次技能调用时的企业是否一致。如果不一致，则发生了主体切换。
2. **切换后行为**：一旦识别到主体切换，应视为新企业从头开始走标准业务流程，不要受旧企业的技能历史影响。
3. **追问方向重置**：主体切换后，追问应从当前技能的自然下游开始，而不是接续旧企业的流程。
   - 例如：A企业刚完成产品推荐→不追问；用户切换到B企业查风险→应追问"是否需要为B企业准备拓户营销材料？"
4. **技能列表解读**："已调用过的技能"列表中可能包含多个企业的混合记录。对于当前企业，只应关注该企业自身是否已达流程闭环，不应被其他企业的技能记录干扰。

## 不追问的场景

- 普通开户咨询、流程问答（非技能调用场景）
- 同一企业在当前会话中已走完"风险预查 → 拓户准备 → 产品推荐"全流程
- 用户表达了明确的结束意图（如"谢谢"、"好的"、"就这样"）
- 技能返回"未找到"（not_found）且用户未继续追问
- 名称歧义（ambiguous）状态，需等待用户确认后再判断

## 技能名称映射

| 技能内部名 | 中文描述 |
|-----------|---------|
| recommend_corporate_customers | 潜客清单/客户详情 |
| check_company_risk | 风险预查 |
| prepare_customer_outreach | 拓户准备 |
| recommend_products | 产品推荐 |
