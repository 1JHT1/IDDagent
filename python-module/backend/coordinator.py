# -*- coding: utf-8 -*-
"""
Coordinator Agent — LLM 意图识别与技能路由

分析用户输入，判断意图：
- 命中了已注册 Skill → 返回 { "action": "skill", "skill": "...", "params": {...} }
- 普通对话 → 返回 { "action": "chat" }
"""
import json
import os
import re
from typing import Optional

from openai import AsyncOpenAI

from skills import skill_registry

# Coordinator 使用的模型（轻量即可，仅做 JSON 结构化输出）
COORDINATOR_MODEL = os.getenv("COORDINATOR_MODEL", "deepseek-chat")

# 缓存客户端实例
_client: Optional[AsyncOpenAI] = None


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        api_key = os.getenv("DEEPSEEK_API_KEY", "")
        base_url = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
        _client = AsyncOpenAI(api_key=api_key, base_url=base_url + "/v1")
    return _client


# ============================================================
# Coordinator 提示词
# ============================================================
def _build_system_prompt() -> str:
    skills_prompt = skill_registry.get_skills_prompt()

    return f"""你是一个任务规划主控智能体。分析用户输入，判断意图并做出路由决策。

## 上下文记忆
系统维护了当前会话的上下文记忆（最近操作的企业主体）。即使用户没有在当前消息中明确提及企业名称，只要意图明确（如「帮我准备拓户材料」「推荐产品」「查下风险」），你仍然应该路由到对应的技能。系统会自动从上下文记忆中补充缺失的企业参数。

## 决策规则（严格遵守）

1. **技能匹配优先**：如果用户意图与以下任一技能匹配，必须返回：
   {{"action": "skill", "skill": "<技能名>", "params": {{}}, "reason": "<中文理由>"}}

2. 当用户输入中包含"拓户"、"潜客"、"开户客户清单"、"推荐客户"、"客户详情"、"客户清单"、"上传客户清单"、"上传清单"、"导入客户"、"上传Excel"、"导入清单"、"上传客户"等关键词时，必须匹配为 recommend_corporate_customers 技能。

3. 当用户输入中包含"查询"、"风险"、"开户风险"、"风险预查"等关键词时，必须匹配为 check_company_risk 技能。
   - 如用户提供了完整企业名称，提取为 company_name 参数
   - 如用户提供了统一信用代码（18位数字+字母），提取为 credit_code 参数
   - company_name 参数只提取企业核心名称部分，去掉"查询"、"是否存在开户风险"等模板词

4. 如果是普通聊天、开户咨询、或其他非技能类对话，返回：
   {{"action": "chat", "reason": "<中文理由>"}}

5. 当用户输入中包含"推荐产品"、"产品推荐"、"产品智荐"、"适合什么产品"、"产品匹配"、"推荐金融产品"等关键词，且**未描述具体资金需求场景**时，必须匹配为 recommend_products 技能。
   - 如用户提供了统一信用代码（18位数字+字母），提取为 credit_code 参数
   - 如用户提供了企业名称，提取为 company_name 参数

6. 当用户输入中**描述了具体的资金需求场景**（如包含具体金额、期限、用途等描述），必须匹配为 match_products_intelligently 技能。
   - 触发特征：用户消息中包含金额数字（如"5千万"、"4000万"、"1亿"）且描述资金场景（如"工程款"、"闲置资金"、"短期理财"、"收益最高"、"保本"等）
   - 将整个用户需求原文提取为 query 参数
   - 如同时提及了企业名称，提取为 company_name 参数
   - 如同时提及了信用代码，提取为 credit_code 参数
   - 注意与规则5的区分：规则5是基于企业画像的预生成推荐，规则6是基于用户实时需求的智能匹配

7. 当用户输入中包含"办理开户"、"协助开户"、"同意开户"、"开始开户"、"开户资料"、"准备开户"等关键词时，必须匹配为 open_corporate_account 技能。
   - 注意与规则3（风险查询）的区分：规则3是查询风险，规则7是实际办理开户动作
   - 如用户提供了企业名称，提取为 company_name 参数
   - 如用户提供了信用代码，提取为 credit_code 参数
   - 如果没有明确企业提供，系统将从上下文记忆中自动补充

## 可用技能

{skills_prompt}

## 示例

用户: "推荐拓户客户清单"
你: {{"action": "skill", "skill": "recommend_corporate_customers", "params": {{}}, "reason": "用户询问拓户客户清单"}}

用户: "查看对公存款智能体的客户详情"
你: {{"action": "skill", "skill": "recommend_corporate_customers", "params": {{"source_id": "corp_deposit_agent"}}, "reason": "用户要求查看具体来源的客户详情"}}

用户: "查看用户自定义上传的客户详情"
你: {{"action": "skill", "skill": "recommend_corporate_customers", "params": {{"source_id": "user_upload"}}, "reason": "用户要求查看自定义上传来源的客户详情"}}

用户: "上传客户清单"
你: {{"action": "skill", "skill": "recommend_corporate_customers", "params": {{}}, "reason": "用户要上传客户清单，返回清单页面展示上传入口"}}

用户: "我要导入客户Excel"
你: {{"action": "skill", "skill": "recommend_corporate_customers", "params": {{}}, "reason": "用户要导入客户Excel，返回清单页面展示上传入口"}}

用户: "查询北京星河科技有限公司是否存在开户风险"
你: {{"action": "skill", "skill": "check_company_risk", "params": {{"company_name": "北京星河科技有限公司"}}, "reason": "用户查询企业开户风险"}}

用户: "查询重庆供应链公司是否存在开户风险"
你: {{"action": "skill", "skill": "check_company_risk", "params": {{"company_name": "重庆供应链公司"}}, "reason": "用户查询企业风险"}}

用户: "查询统一信用代码为91110108MA01B3XK2P的客户的开户风险"
你: {{"action": "skill", "skill": "check_company_risk", "params": {{"credit_code": "91110108MA01B3XK2P"}}, "reason": "用户通过信用代码查询风险"}}

用户: "对公账户开户需要什么材料"
你: {{"action": "chat", "reason": "一般性开户咨询"}}

用户: "我准备对北京星河科技有限公司进行营销，请帮我准备相关材料"
你: {{"action": "skill", "skill": "prepare_customer_outreach", "params": {{"company_name": "北京星河科技有限公司"}}, "reason": "用户需要拓户准备材料"}}

用户: "为北京星河科技有限公司推荐适合的金融产品"
你: {{"action": "skill", "skill": "recommend_products", "params": {{"company_name": "北京星河科技有限公司"}}, "reason": "用户要求为企业推荐产品"}}

用户: "统一信用代码91110108MA01B3XK2P的企业适合什么产品"
你: {{"action": "skill", "skill": "recommend_products", "params": {{"credit_code": "91110108MA01B3XK2P"}}, "reason": "用户通过信用代码查询产品推荐"}}

用户: "帮我准备拓户材料"
你: {{"action": "skill", "skill": "prepare_customer_outreach", "params": {{}}, "reason": "用户需要拓户准备材料（系统将从上下文补充企业信息）"}}

用户: "推荐适合的金融产品"
你: {{"action": "skill", "skill": "recommend_products", "params": {{}}, "reason": "用户要求推荐产品（系统将从上下文补充企业信息）"}}

用户: "查下这家公司的风险"
你: {{"action": "skill", "skill": "check_company_risk", "params": {{}}, "reason": "用户查询风险（系统将从上下文补充企业信息）"}}

用户: "客户最近有一笔5千万的工程款打入，会停留一个月后，需要抽出4千万购买原材料，请推荐收益最高的产品"
你: {{"action": "skill", "skill": "match_products_intelligently", "params": {{"query": "客户最近有一笔5千万的工程款打入，会停留一个月后，需要抽出4千万购买原材料，请推荐收益最高的产品"}}, "reason": "用户描述了具体资金需求场景，需要智能匹配产品"}}

用户: "客户有3000万闲置资金，想要保本稳健的短期理财，期限3个月左右"
你: {{"action": "skill", "skill": "match_products_intelligently", "params": {{"query": "客户有3000万闲置资金，想要保本稳健的短期理财，期限3个月左右"}}, "reason": "用户描述了具体理财需求场景"}}

用户: "北京星河科技有限公司有一笔1亿的融资到账，需要灵活管理，随时可能要用于扩张"
你: {{"action": "skill", "skill": "match_products_intelligently", "params": {{"query": "北京星河科技有限公司有一笔1亿的融资到账，需要灵活管理，随时可能要用于扩张", "company_name": "北京星河科技有限公司"}}, "reason": "用户描述了具体资金管理需求，并提及企业名称"}}

用户: "客户已同意办理开户，请协助办理"
你: {{"action": "skill", "skill": "open_corporate_account", "params": {{}}, "reason": "用户表示客户已同意开户，需要协助办理（系统将从上下文补充企业信息）"}}

用户: "帮我给北京星河科技有限公司办理开户"
你: {{"action": "skill", "skill": "open_corporate_account", "params": {{"company_name": "北京星河科技有限公司"}}, "reason": "用户要求为指定企业办理开户"}}

用户: "准备开户资料"
你: {{"action": "skill", "skill": "open_corporate_account", "params": {{}}, "reason": "用户要求准备开户资料（系统将从上下文补充企业信息）"}}

## 重要规则

- **只输出 JSON，不要输出任何其他文本**
- 不要包裹在 ```json 代码块中
- reason 字段必须用中文简述理由
- 提取 company_name 时不要包含"查询"、"是否存在开户风险"、"的"等模板词语"""


# ============================================================
# Coordinator 调用
# ============================================================
async def route_intent(user_message: str) -> dict:
    """
    分析用户意图，返回路由决策

    返回格式:
        {"action": "skill", "skill": "xxx", "params": {...}, "reason": "..."}
        或 {"action": "chat", "reason": "..."}
    """
    system_prompt = _build_system_prompt()
    client = _get_client()

    # 调用 LLM 进行意图识别
    response = await client.chat.completions.create(
        model=COORDINATOR_MODEL,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message},
        ],
        temperature=0.1,
        max_tokens=300,
    )

    text = response.choices[0].message.content or ""

    # 尝试解析 JSON（可能包裹在 markdown 代码块中）
    json_match = re.search(r"\{[\s\S]*\}", text)
    if json_match:
        try:
            decision = json.loads(json_match.group(0))
            action = decision.get("action")
            if action in ("skill", "chat"):
                # 校验技能名
                if action == "skill":
                    skill_name = decision.get("skill", "")
                    if not skill_registry.get(skill_name):
                        print(f"[Coordinator] LLM 返回未知技能 '{skill_name}'，回退为 chat")
                        return {"action": "chat", "reason": "意图识别返回未知技能"}
                print(f"[Coordinator] 意图: {decision.get('action')}, 理由: {decision.get('reason', '未知')}")
                return decision
        except json.JSONDecodeError:
            pass

    # 解析失败，回退为普通聊天
    print(f"[Coordinator] JSON 解析失败，回退为 chat，原始输出: {text[:200]}")
    return {"action": "chat", "reason": "意图识别解析失败，作为普通对话处理"}
