package com.IDDagent.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContextMemoryService {

    /**
     * 判断文本是否包含上下文指代词（用户用"这家公司"等指代前文企业）。
     * 统一委托 {@link CompanyNameExtractor}，词表集中维护，避免多处定义漂移。
     */
    public static boolean isContextReference(String text) {
        return CompanyNameExtractor.isContextReference(text);
    }

    /**
     * 判断文本是否为泛企业指称（非真实企业名）。
     * 统一委托 {@link CompanyNameExtractor}，词表集中维护，避免多处定义漂移。
     */
    public static boolean isGenericCompanyReference(String text) {
        return CompanyNameExtractor.isGenericCompanyReference(text);
    }

    private final Map<String, ConversationContext> store = new ConcurrentHashMap<>();

    /**
     * 获取会话上下文；若不存在则创建并注册到 store 后返回。
     * 注意：不能用 getOrDefault 返回临时实例——调用方（如 handleMulti 设置
     * pipelinePlan）可能直接修改返回对象的字段，临时实例不会写入 store，
     * 会导致管道计划快照等状态丢失（表现为后续 resume 时 pipelinePlan 为空、
     * report-completed 走 skipped 分支，管道永不推进）。
     */
    public ConversationContext get(String conversationId) {
        return store.computeIfAbsent(conversationId, k -> new ConversationContext());
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
     * 记录暂停等待用户补充时的提示文案（如"请上传该企业的营业执照图片以进行信息核实。"），
     * 供多意图管道暂停时透传给前端任务清单卡片，明确提醒用户需要上传附件或补充信息
     */
    public void setPendingInputHint(String conversationId, String hint) {
        if (hint == null || hint.isEmpty()) return;
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.pendingInputHint = hint;
    }

    /**
     * 清除待处理技能
     */
    public void clearPendingSkill(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) {
            ctx.pendingSkillName = "";
            ctx.pendingSkillParams.clear();
            ctx.pendingInputHint = "";
        }
    }

    /**
     * 设置等待报告生成完成的任务（generate_report 返回 redirect 跳转 H5 编辑页时设置）。
     * 管道任务进入异步报告生成阶段后挂起，直到报告完成（前端轮询到报告 completed 后
     * 调用 report-completed 接口）才清除并推进管道。
     */
    public void setWaitingReportTask(String conversationId, Map<String, Object> task) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.waitingReportTask = task;
    }

    /** 清除等待报告标记（报告生成完成、管道推进后调用） */
    public void clearWaitingReport(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) ctx.waitingReportTask = null;
    }
    public void updateAttachment(String conversationId, String attachmentUrl) {
        if (attachmentUrl == null || attachmentUrl.isEmpty()) return;
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.attachmentUrl = attachmentUrl;
    }

    public void clearAttachment(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) ctx.attachmentUrl = "";
    }

    public void clear(String conversationId) {
        store.remove(conversationId);
        cancelledFlags.remove(conversationId);
    }

    // ============================================================
    // 强制终止对话标记
    // ============================================================

    private final Map<String, Boolean> cancelledFlags = new ConcurrentHashMap<>();

    /** 标记该会话的流式生成为终止状态（前端点击"强制终止"时调用） */
    public void cancel(String conversationId) {
        cancelledFlags.put(conversationId, true);
    }

    /** 该会话是否被标记终止 */
    public boolean isCancelled(String conversationId) {
        return Boolean.TRUE.equals(cancelledFlags.get(conversationId));
    }

    /** 清除终止标记（每次新消息开始时重置） */
    public void clearCancelled(String conversationId) {
        cancelledFlags.remove(conversationId);
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
        /** 暂停等待用户补充时的提示文案（如"请上传营业执照图片"） */
        public String pendingInputHint = "";
        public String attachmentUrl = "";

        /** 多意图管道剩余任务队列（List<Map<String,Object>>，每项含 skill/params/order/_index） */
        public List<Map<String, Object>> pendingPipeline = new ArrayList<>();

        /** 多意图管道完整计划快照（List<Map<String,Object>>，每项含 skill/label/order），
         *  供暂停恢复时重建 planning 事件（含已完成任务，前端据此恢复任务清单） */
        public List<Map<String, Object>> pipelinePlan = new ArrayList<>();

        /** 等待异步报告生成完成的任务信息（generate_report redirect 阶段设置，
         *  含 skill/label/order；报告完成后由 report-completed 接口清除并推进管道） */
        public Map<String, Object> waitingReportTask = null;

        public boolean isEmpty() {
            return (companyName == null || companyName.isEmpty())
                    && (creditCode == null || creditCode.isEmpty());
        }

        /** 是否有待处理的技能（技能等待用户回复补充信息） */
        public boolean hasPendingSkill() {
            return pendingSkillName != null && !pendingSkillName.isEmpty();
        }

        /** 是否有多意图管道待恢复 */
        public boolean hasPendingPipeline() {
            return pendingPipeline != null && !pendingPipeline.isEmpty();
        }

        /** 是否在等待异步报告生成完成（generate_report redirect 阶段设置） */
        public boolean isWaitingReport() {
            return waitingReportTask != null;
        }
    }
}
