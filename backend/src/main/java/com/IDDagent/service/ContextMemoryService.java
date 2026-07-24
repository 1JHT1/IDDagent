package com.IDDagent.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContextMemoryService {

    private final Map<String, ConversationContext> store = new ConcurrentHashMap<>();

    public ConversationContext get(String conversationId) {
        return store.getOrDefault(conversationId, new ConversationContext());
    }

    public void update(String conversationId, String companyName, String creditCode) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        if (companyName != null && !companyName.isEmpty()) ctx.companyName = companyName;
        if (creditCode != null && !creditCode.isEmpty()) ctx.creditCode = creditCode;
    }

    /**
     * 设置待处理技能（技能返回 info_needed / candidates 时调用）
     * 后续用户消息将直接路由到该技能，跳过 Coordinator/LLM
     */
    public void setPendingSkill(String conversationId, String skillName, Map<String, Object> params) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.pendingSkillName = skillName;
        ctx.pendingSkillParams.clear();
        if (params != null) {
            ctx.pendingSkillParams.putAll(params);
        }
    }

    /**
     * 清除待处理技能
     */
    public void clearPendingSkill(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) {
            ctx.pendingSkillName = "";
            ctx.pendingSkillParams.clear();
        }
    }

    public void clear(String conversationId) {
        store.remove(conversationId);
    }

    public static class ConversationContext {
        public String companyName = "";
        public String creditCode = "";
        /** 待处理技能名称（技能正在等待用户补充信息） */
        public String pendingSkillName = "";
        /** 待处理技能的已有参数 */
        public Map<String, Object> pendingSkillParams = new LinkedHashMap<>();
        /** 待处理技能连续重试次数（防死循环） */
        public int pendingSkillRetry = 0;

        public boolean isEmpty() {
            return (companyName == null || companyName.isEmpty())
                    && (creditCode == null || creditCode.isEmpty());
        }

        /** 是否有待处理的技能（技能等待用户回复补充信息） */
        public boolean hasPendingSkill() {
            return pendingSkillName != null && !pendingSkillName.isEmpty();
        }
    }
}
