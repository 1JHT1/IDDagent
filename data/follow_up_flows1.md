# 银行尽职调查经理标准追问流程

## 概述

本文档定义了银行尽职调查经理在与智能助手交互时的标准业务流程。助手在完成当前问题应答后，应参考此文档预测用户下一步最可能的需求，主动追问以引导流程。

## 核心业务场景与追问链路

### 场景 A：营业执照核实 → 风险识别 → 尽调报告历史查询 → 尽调报告生成

用户先核实营业执照，然后查询企业风险，再查看历史尽调报告，最后生成新报告。
```

营业执照核实（verify_license）
└─ 追问：是否需要查询「{企业名}」的工商税务等风险信息？
└─ 风险识别（check_company_risk）
└─ 追问：是否需要查看「{企业名}」的历史尽调报告？
└─ 尽调报告历史查询（query_due_diligence_reports）
└─ 追问：是否需要为「{企业名}」生成新的尽调报告？
└─ 尽调报告生成（generate_due_diligence_report）
└─ 结束

```
### 场景 B：直接风险识别 → 尽调报告历史查询 → 尽调报告生成

用户直接查询某企业风险，后续跟进历史报告查询和生成新报告。
```

风险识别（check_company_risk）
└─ 追问：是否需要查看「{企业名}」的历史尽调报告？
└─ 尽调报告历史查询（query_due_diligence_reports）
└─ 追问：是否需要为「{企业名}」生成新的尽调报告？
└─ 尽调报告生成（generate_due_diligence_report）
└─ 结束

```
### 场景 C：直接历史报告查询 → 尽调报告生成

用户直接查询历史尽调报告，后续跟进生成新报告。

```
尽调报告历史查询（query_due_diligence_reports）
└─ 追问：是否需要为「{企业名}」生成新的尽调报告？
└─ 尽调报告生成（generate_due_diligence_report）
└─ 结束

```
### 场景 D：直接营业执照核实 → 风险识别

用户直接核实营业执照，后续跟进风险识别，并最终完成历史查询和报告生成。
```

营业执照核实（verify_license）
└─ 追问：是否需要查询「{企业名}」的工商税务等风险信息？
└─ 风险识别（check_company_risk）
└─ 追问：是否需要查看「{企业名}」的历史尽调报告？
└─ 尽调报告历史查询（query_due_diligence_reports）
└─ 追问：是否需要为「{企业名}」生成新的尽调报告？
└─ 尽调报告生成（generate_due_diligence_report）
└─ 结束

```
## 追问生成规则

1. **追问必须自然、口语化**，以"是否需要"开头，例如"是否需要查询「北京星河科技有限公司」的工商税务等风险信息？"
2. **追问必须包含当前企业名称**（如果有），使用中文书名号「」包裹
3. **追问针对当前企业主体**，不要跨企业追问
4. **单次只追问一个下一步动作**，不要列出多个选项
5. **优先追问风险识别或尽调报告生成**，这两者是尽调经理最核心的需求
6. **追问优先级**（从高到低）：
   - 尽调报告生成（generate_due_diligence_report）—— 尽调最终产出物
   - 尽调报告历史查询（query_due_diligence_reports）—— 避免重复工作
   - 风险识别（check_company_risk）—— 尽调核心依据
   - 营业执照核实（verify_license）—— 基础信息验证
7. **不要追问当前已完成的技能**，避免循环
8. **如果用户已在当前会话中完成了所有核心流程**（营业执照核实/风险识别 + 历史查询 + 报告生成），则不要追问

## 严格反循环规则（优先级最高，必须遵守）

当判断是否应该追问时，必须先执行以下检查。如果命中任一条件，立即输出 `{"suggestion": null}`：

| 条件 | 说明 |
|------|------|
| 刚完成的技能是 `generate_due_diligence_report` 且已调用过 `query_due_diligence_reports` | 报告生成后若历史查询也做过，该企业流程闭环 → 不追问 |
| 刚完成的技能是 `query_due_diligence_reports` 且已调用过 `generate_due_diligence_report` | 历史查询后若报告生成也做过，该企业流程闭环 → 不追问 |
| 刚完成的技能是 `generate_due_diligence_report`，且候选追问也是 `generate_due_diligence_report` | 严禁追问自己 → 不追问 |
| 刚完成的技能是 `verify_license` 且已调用过 `check_company_risk` | 营业执照核实后若风险识别也做过，按流程继续追问历史报告（允许） |
| 已调用技能列表中同时包含 `verify_license`（或 `check_company_risk`）、`query_due_diligence_reports`、`generate_due_diligence_report` | 三大核心技能全部完成 → 不追问 |
| 刚完成的技能是 `verify_license` 且参数不含 `source_id`（即非详版核实） | 基础核实后向上追问风险识别 |
| 刚完成的技能是 `verify_license` 且参数包含 `source_id`（即详版核实） | 详版核实后向上追问风险识别，而非再次详版核实 |

**追问走向速查表**（当前技能 → 唯一允许的追问方向）：

| 当前技能 | 允许的追问 | 禁止的追问 |
|----------|-----------|------------|
| `verify_license` (基础核实结果) | `check_company_risk` | `verify_license`、`query_due_diligence_reports`、`generate_due_diligence_report` |
| `verify_license` (详版核实结果) | `check_company_risk` | `verify_license`、`query_due_diligence_reports`、`generate_due_diligence_report` |
| `check_company_risk` (result) | `query_due_diligence_reports` | `check_company_risk`、`generate_due_diligence_report`（不可跳过历史查询直接生成） |
| `query_due_diligence_reports` (result) | `generate_due_diligence_report`（仅当报告生成未做过） | `query_due_diligence_reports`、`check_company_risk` |
| `generate_due_diligence_report` (result) | 无（不追问） | `generate_due_diligence_report`、`query_due_diligence_reports`、`check_company_risk`、`verify_license` |

## 主体切换规则（当用户从企业A切换到企业B时）

1. **识别主体切换**：比较当前 `company_name` / `credit_code` 与"已调用技能"列表中上一次技能调用时的企业是否一致。如果不一致，则发生了主体切换。
2. **切换后行为**：一旦识别到主体切换，应视为新企业从头开始走标准业务流程，不要受旧企业的技能历史影响。
3. **追问方向重置**：主体切换后，追问应从当前技能的自然下游开始，而不是接续旧企业的流程。
   - 例如：A企业刚完成尽调报告生成→不追问；用户切换到B企业查风险→应追问"是否需要查看B企业的历史尽调报告？"
4. **技能列表解读**："已调用过的技能"列表中可能包含多个企业的混合记录。对于当前企业，只应关注该企业自身是否已达流程闭环，不应被其他企业的技能记录干扰。

## 不追问的场景

- 普通尽调流程咨询、制度问答（非技能调用场景）
- 同一企业在当前会话中已走完"营业执照核实（或风险识别）→ 历史报告查询 → 尽调报告生成"全流程
- 用户表达了明确的结束意图（如"谢谢"、"好的"、"就这样"）
- 技能返回"未找到"（not_found）且用户未继续追问
- 名称歧义（ambiguous）状态，需等待用户确认后再判断
- 尽调报告生成后不再追问（流程自然结束）

## 技能名称映射

| 技能内部名 | 中文描述 |
|-----------|---------|
| verify_license | 营业执照核实 |
| check_company_risk | 风险识别 |
| query_due_diligence_reports | 尽调报告历史查询 |
| generate_due_diligence_report | 尽调报告生成 |
```
