# -*- coding: utf-8 -*-
"""
Follow-Up Agent — 主动追问预测

在技能应答完成后，分析当前会话上下文，参考标准业务流程文档，
预测用户下一步最可能的意图，生成自然语言的追问建议。
"""
import json
import os
import re
from pathlib import Path
from typing import Optional

from openai import AsyncOpenAI

# 追问流程图文档路径
WORKFLOW_FILE = Path(__file__).parent / "data" / "follow_up_workflows.md"

# 缓存
_workflow_text: Optional[str] = None
_client: Optional[AsyncOpenAI] = None


def _load_workflow() -> str:
    """加载标准追问流程文档"""
    global _workflow_text
    if _workflow_text is None:
        if WORKFLOW_FILE.exists():
            _workflow_text = WORKFLOW_FILE.read_text(encoding="utf-8")
        else:
            _workflow_text = ""
    return _workflow_text


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        api_key = os.getenv("DEEPSEEK_API_KEY", "")
        base_url = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
        _client = AsyncOpenAI(api_key=api_key, base_url=base_url + "/v1")
    return _client


# ============================================================
# 追问预测
# ============================================================
async def predict_follow_up(
    skill_name: str,
    skill_action: str,
    company_name: str = "",
    credit_code: str = "",
    conversation_skills: Optional[list[str]] = None,
    current_company_skills: Optional[list[str]] = None,
) -> Optional[str]:
    """
    预测用户下一步意图，返回追问建议文本。

    参数:
        skill_name: 刚执行的技能名
        skill_action: 技能返回的 action（result / not_found / ambiguous / ...）
        company_name: 当前企业名称（上下文）
        credit_code: 当前企业信用代码
        conversation_skills: 当前会话中所有已调用过的技能（按顺序，可能跨企业混合）
        current_company_skills: 当前企业已调用过的技能（仅当前企业，用于反循环判断）

    返回:
        追问文本，如 "是否需要为「北京星河科技有限公司」推荐适合的金融产品？"
        如果判断无需追问，返回 None
    """
    # ── 不追问的场景 ──
    if skill_action in ("not_found", "ambiguous", "error"):
        return None

    # 非技能调用（普通聊天）不追问
    if not skill_name:
        return None

    conversation_skills = conversation_skills or []
    current_company_skills = current_company_skills or []

    # ── 构建提示词 ──
    workflow = _load_workflow()

    all_skills_done = "、".join(conversation_skills) if conversation_skills else "无"
    company_skills_done = "、".join(current_company_skills) if current_company_skills else "无"

    user_prompt = f"""## 当前上下文

- 刚完成的技能：{skill_name}
- 当前企业：{company_name or '无'}
- 统一信用代码：{credit_code or '无'}
- 本次会话所有已调用过的技能（按顺序，可能跨企业）：{all_skills_done}
- 当前企业「{company_name or '无'}」已调用过的技能：{company_skills_done}

## 任务

根据上述上下文和标准业务流程，预测用户下一步最可能的意图，生成一句自然的追问。

## 输出要求

- 只输出一个 JSON 对象，不要输出其他任何内容
- 如果判断应该追问，输出：{{"suggestion": "追问文本"}}
- 如果判断不应追问，输出：{{"suggestion": null}}
- 追问文本必须以"是否需要"开头，使用口语化中文
- **必须参考「严格反循环规则」和「主体切换规则」做出判断**"""

    system_prompt = f"""你是一个银行对公客户经理的智能助手。你的任务是在完成当前应答后，主动预测用户的下一步需求。

## 标准业务流程

{workflow}

## 重要规则（必须严格遵守）

- **反循环是第一优先级**：严格按照「严格反循环规则」和「追问走向速查表」判断，禁止追问当前已完成的技能
- **判断流程闭环**：参考「当前企业已调用过的技能」判断该企业是否已走完风险+拓户+产品全流程，是则不追问
- **主体切换识别**：当当前企业发生变化时，视为新企业从头开始，不要受旧企业技能历史影响
- 追问必须自然亲切，以"是否需要"开头
- 如果当前企业有名称，追问中必须包含「企业名」
- 不要在已经完成流程闭环后继续追问"""

    try:
        client = _get_client()
        response = await client.chat.completions.create(
            model=os.getenv("FOLLOW_UP_MODEL", "deepseek-chat"),
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.3,
            max_tokens=150,
        )

        text = response.choices[0].message.content or ""

        # 解析 JSON
        json_match = re.search(r"\{[\s\S]*\}", text)
        if json_match:
            result = json.loads(json_match.group(0))
            suggestion = result.get("suggestion")
            if suggestion and isinstance(suggestion, str) and suggestion.strip():
                print(f"[FollowUp] 追问建议: {suggestion}")
                return suggestion.strip()

        print(f"[FollowUp] 无需追问 (原始输出: {text[:100]})")
        return None

    except Exception as e:
        print(f"[FollowUp] 预测失败: {e}")
        return None
