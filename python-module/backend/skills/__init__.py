# -*- coding: utf-8 -*-
"""
Skill 插件注册中心
提供可扩展的技能注册、发现、调用机制
"""
from typing import Callable, Any, Optional
from dataclasses import dataclass, field


# ============================================================
# Skill 定义
# ============================================================
@dataclass
class Skill:
    """技能插件"""

    name: str  # 唯一标识
    description: str  # LLM 意图识别用的自然语言描述
    handler: Callable  # 执行函数 async (user_id, params) -> dict

    # 传递给 LLM 的 JSON Schema 参数定义
    parameters: dict = field(default_factory=dict)

    def to_prompt_desc(self) -> str:
        """生成给 LLM 的技能描述文本"""
        desc = f"- **{self.name}**: {self.description}"
        if self.parameters:
            params_desc = ", ".join(
                f"{k} (示例: {v.get('example', '')})"
                for k, v in self.parameters.items()
            )
            desc += f"\n  参数: {params_desc}"
        return desc


# ============================================================
# Skill 注册中心
# ============================================================
class SkillRegistry:
    """技能注册中心，管理所有已注册的技能插件"""

    def __init__(self):
        self._skills: dict[str, Skill] = {}

    def register(self, skill: Skill):
        """注册技能"""
        self._skills[skill.name] = skill
        print(f"[SkillRegistry] 已注册技能: {skill.name}")

    def get(self, name: str) -> Optional[Skill]:
        """获取技能"""
        return self._skills.get(name)

    def get_skills_prompt(self) -> str:
        """生成所有技能描述，注入 Coordinator Agent 的提示词"""
        if not self._skills:
            return "（当前无可用技能）"

        lines = []
        for i, skill in enumerate(self._skills.values(), 1):
            lines.append(f"{i}. {skill.to_prompt_desc()}")
        return "\n".join(lines)

    def list_skill_names(self) -> list[str]:
        """列出所有已注册技能名"""
        return list(self._skills.keys())

    async def invoke(self, name: str, user_id: str, params: dict) -> dict:
        """调用指定技能"""
        skill = self._skills.get(name)
        if not skill:
            return {"error": f"技能 '{name}' 未注册"}
        try:
            if hasattr(skill.handler, "__call__"):
                import asyncio
                result = skill.handler(user_id, params)
                if asyncio.iscoroutine(result):
                    result = await result
                return result
        except Exception as e:
            return {"error": f"技能执行失败: {str(e)}"}


# ============================================================
# 全局注册中心实例
# ============================================================
skill_registry = SkillRegistry()
