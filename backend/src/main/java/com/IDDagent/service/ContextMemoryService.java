package com.IDDagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContextMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ContextMemoryService.class);

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
    // 任务规划（pendingPlan）
    // ============================================================

    /**
     * 设置待执行的任务规划（多意图 → 规划步骤列表）。
     * 重置 planIndex=0、planActive=true、planConfirming=false，并生成新的 planId
     * （前端规划面板按 planId 定位：穿插新规划时生成新 id，旧面板保留挂起态互不干扰）；
     * 步骤为空时不激活规划。
     */
    public void setPendingPlan(String conversationId, List<PlanStep> steps) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.pendingPlan.clear();
        if (steps != null) {
            ctx.pendingPlan.addAll(steps);
        }
        ctx.planIndex = 0;
        ctx.planActive = !ctx.pendingPlan.isEmpty();
        ctx.planConfirming = false;
        ctx.planId = UUID.randomUUID().toString();
    }

    /** 清空任务规划（全部完成或失败兜底时调用，防止会话卡死） */
    public void clearPendingPlan(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) {
            ctx.pendingPlan.clear();
            ctx.planIndex = 0;
            ctx.planActive = false;
            ctx.planConfirming = false;
        }
    }

    /** 标记当前步骤已完成、等待用户确认是否继续下一步（步骤真正结束后调用） */
    public void setPlanConfirming(String conversationId, boolean confirming) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) {
            ctx.planConfirming = confirming;
        }
    }

    /** 标记/清除"是否恢复挂起规划"的确认状态（穿插的新意图完成后、用户回复前） */
    public void setResumeConfirming(String conversationId, boolean confirming) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) {
            ctx.resumeConfirming = confirming;
        }
    }

    /** 获取当前规划步骤：planActive 且 planIndex 在范围内时返回，否则 null */
    public PlanStep getCurrentPlanStep(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || !ctx.planActive) return null;
        if (ctx.planIndex >= 0 && ctx.planIndex < ctx.pendingPlan.size()) {
            return ctx.pendingPlan.get(ctx.planIndex);
        }
        return null;
    }

    /** 标记当前规划步骤是否需要用户输入（技能返回 info_needed/candidates 且处于规划模式时调用） */
    public void setPlanStepNeedsInput(String conversationId, boolean needsInput) {
        PlanStep step = getCurrentPlanStep(conversationId);
        if (step != null) {
            step.needsInput = needsInput;
            step.status = needsInput ? PlanStatus.WAITING_INPUT : PlanStatus.RUNNING;
        }
    }

    /** 推进规划：planIndex++，返回是否还有下一步 */
    public boolean advancePlan(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || !ctx.planActive) return false;
        ctx.planIndex++;
        return ctx.planIndex < ctx.pendingPlan.size();
    }

    /**
     * 构建当前会话的任务规划状态快照（供 plan_status SSE 事件与 GET /api/plan/{id}/status 使用）：
     * - 规划激活 → 从 pendingPlan 读取步骤与 planIndex/planConfirming；
     * - 规划挂起（意图穿插中）→ 从 suspendedPlan 读取步骤与 suspendedIndex/suspendedConfirming，suspended=true；
     * - 无规划 → active=false、steps 为空。
     * 注意：步骤列表在 clearPendingPlan/discardSuspendedPlan 后为空，调用方须在这些操作之前取快照。
     */
    public Map<String, Object> getPlanStatusData(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (ctx == null) {
            data.put("active", false);
            data.put("steps", new ArrayList<>());
            data.put("index", 0);
            data.put("confirming", false);
            data.put("suspended", false);
            data.put("planId", "");
            return data;
        }
        SuspendFrame topFrame = ctx.suspendStack.peek();
        boolean suspended = !ctx.planActive && topFrame != null;
        List<PlanStep> steps = ctx.planActive ? ctx.pendingPlan : (topFrame == null ? null : topFrame.steps);
        int index = ctx.planActive ? ctx.planIndex : (topFrame == null ? 0 : topFrame.index);
        boolean confirming = ctx.planActive ? ctx.planConfirming : (topFrame != null && topFrame.confirming);
        List<Map<String, Object>> stepList = new ArrayList<>();
        if (steps != null) {
            for (PlanStep s : steps) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("skill", s.skill);
                m.put("summary", s.summary == null ? "" : s.summary);
                m.put("status", s.status.name());
                m.put("needsInput", s.needsInput);
                stepList.add(m);
            }
        }
        data.put("active", ctx.planActive);
        data.put("steps", stepList);
        data.put("index", index);
        data.put("confirming", confirming);
        data.put("suspended", suspended);
        data.put("planId", ctx.planId == null ? "" : ctx.planId);
        return data;
    }

    // ============================================================
    // 意图穿插：规划挂起与恢复（suspend/resume）
    // ============================================================

    /**
     * 挂起当前激活的任务规划（意图穿插时调用）：将当前规划整体压入挂起规划栈
     * （SuspendFrame 深拷贝快照），同时清空激活状态，使后续消息按新意图正常路由；
     * 新意图处理完成后调用 restoreSuspendedPlan 弹出栈顶断点再续。
     * 支持多层嵌套穿插：嵌套规划执行中再次穿插时逐层压栈，恢复时按后进先出逐层弹出。
     */
    public void suspendPlan(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || !ctx.planActive || ctx.pendingPlan.isEmpty()) return;
        List<PlanStep> snapshot = new ArrayList<>();
        for (PlanStep s : ctx.pendingPlan) {
            snapshot.add(copyStep(s));
        }
        ctx.suspendStack.push(new SuspendFrame(snapshot, ctx.planIndex, ctx.planConfirming, ctx.planId));
        ctx.pendingPlan.clear();
        ctx.planIndex = 0;
        ctx.planActive = false;
        ctx.planConfirming = false;
        log.info("Suspended active plan for conversation {} at step {}/{} (stack depth {})",
                conversationId, ctx.suspendStack.peek().index + 1, snapshot.size(), ctx.suspendStack.size());
    }

    /** 是否存在挂起的规划（挂起栈非空，意图穿插待续） */
    public boolean hasSuspendedPlan(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        return ctx != null && !ctx.suspendStack.isEmpty();
    }

    /**
     * 恢复挂起的规划（弹出挂起栈栈顶）：将 SuspendFrame 快照回填 pendingPlan，
     * 恢复 planIndex/planActive/planConfirming 到挂起时状态。
     * 调用方负责后续推进（重跑当前步骤或重发确认卡片）。
     *
     * @return 恢复后的当前步骤；无挂起规划时返回 null
     */
    public PlanStep restoreSuspendedPlan(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.suspendStack.isEmpty()) return null;
        SuspendFrame frame = ctx.suspendStack.pop();
        ctx.pendingPlan.clear();
        ctx.pendingPlan.addAll(frame.steps);
        ctx.planIndex = frame.index;
        ctx.planActive = true;
        ctx.planConfirming = frame.confirming;
        ctx.resumeConfirming = false;
        // 还原挂起前的 planId：穿插新规划生成的 planId 属于穿插面板，主规划面板按挂起前 id 定位；
        // 不还原则恢复后所有状态快照都发到穿插面板 id，穿插前的旧面板收不到终态而停留"执行中"
        ctx.planId = frame.planId;
        log.info("Restored suspended plan for conversation {} at step {}/{} (stack depth {})",
                conversationId, ctx.planIndex + 1, ctx.pendingPlan.size(), ctx.suspendStack.size());
        return ctx.planIndex >= 0 && ctx.planIndex < ctx.pendingPlan.size()
                ? ctx.pendingPlan.get(ctx.planIndex) : null;
    }

    /**
     * 丢弃挂起规划栈栈顶（用户选择不恢复穿插前的任务时调用）：
     * 弹出当前层挂起快照并清除确认状态，该层旧规划彻底结束；
     * 若栈中仍有外层挂起（嵌套穿插），由调用方继续询问是否恢复下一层。
     */
    public void discardSuspendedPlan(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.suspendStack.isEmpty()) return;
        SuspendFrame frame = ctx.suspendStack.pop();
        // 被丢弃层规划已收尾（穿插任务的成果已展示）：planId 语义回到被丢弃层 id，
        // 之后发出的空快照按旧 id 定位，清理当前显示的挂起面板
        if (!frame.planId.isEmpty()) {
            ctx.planId = frame.planId;
        }
        ctx.resumeConfirming = false;
        log.info("Discarded suspended plan for conversation {} (stack depth {})",
                conversationId, ctx.suspendStack.size());
    }

    /** 深拷贝规划步骤（挂起快照用，避免穿插期间原步骤参数被外部修改污染） */
    private static PlanStep copyStep(PlanStep s) {
        PlanStep copy = new PlanStep(s.skill, s.params, s.needsInput, s.priority);
        copy.status = s.status;
        copy.summary = s.summary;
        return copy;
    }

    /**
     * 挂起规划栈帧：一层穿插挂起的完整快照（步骤深拷贝 + 断点下标 + 确认阶段标记 + 原 planId）。
     * 栈式结构支持多层嵌套穿插：push 挂起、pop 恢复，恢复/穿透/展示均取栈顶帧。
     */
    public static class SuspendFrame {
        /** 挂起时规划步骤序列（深拷贝，穿插期间外部修改不影响快照） */
        public final List<PlanStep> steps;
        /** 挂起时的执行下标（恢复后从此步继续） */
        public final int index;
        /** 挂起时是否处于步骤确认阶段（恢复后重新发确认卡片） */
        public final boolean confirming;
        /** 挂起前规划的 planId（恢复时必须还原，否则前端旧面板收不到终态快照） */
        public final String planId;

        public SuspendFrame(List<PlanStep> steps, int index, boolean confirming, String planId) {
            this.steps = steps;
            this.index = index;
            this.confirming = confirming;
            this.planId = planId == null ? "" : planId;
        }
    }

    // ============================================================
    // 待确认澄清（pendingClarification，Phase 4 意图冲突用）
    // ============================================================

    /** 设置待确认澄清上下文（发送 clarification 事件前调用） */
    public void setPendingClarification(String conversationId, Map<String, Object> context) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.pendingClarification.clear();
        if (context != null) {
            ctx.pendingClarification.putAll(context);
        }
    }

    /** 取走待确认澄清上下文（用户回复选项后调用），无待确认时返回 null */
    public Map<String, Object> consumePendingClarification(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.pendingClarification.isEmpty()) return null;
        Map<String, Object> result = new LinkedHashMap<>(ctx.pendingClarification);
        ctx.pendingClarification.clear();
        return result;
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
        public String attachmentUrl = "";
        /** 任务规划步骤列表（多意图串行执行） */
        public List<PlanStep> pendingPlan = new ArrayList<>();
        /** 当前规划的标识（前端规划面板按此定位，穿插新规划时重新生成） */
        public String planId = "";
        /** 当前规划步骤下标 */
        public int planIndex = 0;
        /** 规划是否激活 */
        public boolean planActive = false;
        /** 当前步骤已完成、等待用户确认是否继续下一步（步骤间确认机制，StepConfirm 用） */
        public boolean planConfirming = false;
        /** 穿插的新意图完成后等待用户确认是否恢复挂起的旧规划（resume_confirm 卡片阶段） */
        public boolean resumeConfirming = false;
        /** 待确认澄清上下文（意图冲突时暂存，等待用户选择） */
        public Map<String, Object> pendingClarification = new LinkedHashMap<>();
        /** 挂起规划栈（意图穿插时深拷贝压栈，支持多层嵌套穿插；新意图完成后逐层断点再续） */
        public Deque<SuspendFrame> suspendStack = new ArrayDeque<>();

        public boolean isEmpty() {
            return (companyName == null || companyName.isEmpty())
                    && (creditCode == null || creditCode.isEmpty());
        }

        /** 是否有待处理的技能（技能等待用户回复补充信息） */
        public boolean hasPendingSkill() {
            return pendingSkillName != null && !pendingSkillName.isEmpty();
        }
    }

    /** 规划步骤状态 */
    public enum PlanStatus {
        /** 初始等待执行 */
        PENDING,
        /** 等待用户输入补充参数（info_needed/candidates） */
        WAITING_INPUT,
        /** 执行中 */
        RUNNING,
        /** 已完成 */
        DONE,
        /** 执行失败 */
        FAILED,
        /** 等待外部异步完成（如尽调报告在 H5 编辑页面异步生成，前端轮询到完成后通知后端收尾） */
        WAITING_EXTERNAL
    }

    /** 任务规划步骤：单个技能 + 参数 + 状态 + 优先级 + 结果摘要 */
    public static class PlanStep {
        public String skill;
        public Map<String, Object> params = new LinkedHashMap<>();
        public PlanStatus status = PlanStatus.PENDING;
        public boolean needsInput = false;
        /** 执行优先级（越小越先执行，来自 LLM intents.priority，默认 0 按数组顺序） */
        public int priority = 0;
        /** 步骤结果摘要（如"风险查询（小米）"），供 plan_summary 汇总使用 */
        public String summary = "";

        public PlanStep(String skill, Map<String, Object> params, boolean needsInput) {
            this(skill, params, needsInput, 0);
        }

        public PlanStep(String skill, Map<String, Object> params, boolean needsInput, int priority) {
            this.skill = skill;
            if (params != null) {
                this.params.putAll(params);
            }
            this.needsInput = needsInput;
            this.priority = priority;
            this.status = needsInput ? PlanStatus.WAITING_INPUT : PlanStatus.PENDING;
        }
    }
}
