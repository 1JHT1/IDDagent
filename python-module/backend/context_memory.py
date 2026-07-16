# -*- coding: utf-8 -*-
"""
会话上下文记忆模块

维护每个会话当前正在操作的企业主体（公司名称 + 统一信用代码），
支持跨轮对话的上下文连贯性：

- 用户先查询 A 企业风险，后续说「帮我准备拓户材料」→ 自动关联到 A 企业
- 用户在同一会话中切换到 B 企业 → 上下文自动更新为 B
- 每个会话独立，互不干扰
"""
from threading import Lock
from typing import Optional


class ConversationContext:
    """单个会话的上下文信息"""

    __slots__ = ("company_name", "credit_code")

    def __init__(self, company_name: str = "", credit_code: str = ""):
        self.company_name = company_name
        self.credit_code = credit_code

    def is_empty(self) -> bool:
        return not self.company_name and not self.credit_code

    def to_dict(self) -> dict:
        return {
            "company_name": self.company_name,
            "credit_code": self.credit_code,
        }


class ContextMemory:
    """
    会话上下文记忆（线程安全单例）

    使用方式:
        memory = context_memory  # 全局单例
        ctx = memory.get("conv-123")
        memory.update("conv-123", company_name="北京星河科技有限公司", credit_code="91110108MA01B3XK2P")
        memory.clear("conv-123")
    """

    def __init__(self):
        self._store: dict[str, ConversationContext] = {}
        self._lock = Lock()

    def get(self, conversation_id: str) -> ConversationContext:
        """获取会话上下文（不存在则返回空上下文）"""
        with self._lock:
            return self._store.get(conversation_id, ConversationContext())

    def update(
        self,
        conversation_id: str,
        company_name: Optional[str] = None,
        credit_code: Optional[str] = None,
    ) -> None:
        """更新会话上下文（部分字段可增量更新）"""
        with self._lock:
            if conversation_id not in self._store:
                self._store[conversation_id] = ConversationContext()
            ctx = self._store[conversation_id]
            if company_name:
                ctx.company_name = company_name
            if credit_code:
                ctx.credit_code = credit_code

    def clear(self, conversation_id: str) -> None:
        """清除会话上下文"""
        with self._lock:
            self._store.pop(conversation_id, None)


# 全局单例
context_memory = ContextMemory()
