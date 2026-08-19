package com.IDDagent.controller;

import com.IDDagent.model.*;
import com.IDDagent.service.*;
import com.IDDagent.skill.Skill;
import com.IDDagent.skill.SkillRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 模糊匹配候选列表「以上选项均不是」回复的识别模式（各技能候选卡片点击按钮后发送的固定短语） */
    private static final Pattern NONE_OF_ABOVE_PATTERN = Pattern.compile(
            "^(?:以上(?:选项)?(?:均|都)?不是|以上(?:选项)?均不是|以上(?:选项)?都不是|均不是|都不是|没有(?:匹配|符合|我要|想找|想选)的|都不符合|都没有)$");
    /** 模糊匹配候选列表「以上选项均不是」的统一引导回复 */
    private static final String NONE_OF_ABOVE_REPLY =
            "以上选项均不是您要查询的企业。请提供准确的企业名称，或直接输入 18 位统一社会信用代码，我将为您重新查询。";

    private final ConversationService conversationService;
    private final ContextMemoryService contextMemoryService;
    private final CoordinatorService coordinatorService;
    private final FollowUpService followUpService;
    private final AgentService agentService;
    private final SkillRegistry skillRegistry;
    private final IntentPlannerService intentPlannerService;
    private final IntentConflictResolver intentConflictResolver;
    private final ReportTaskStore reportTaskStore;

    public ChatController(ConversationService conversationService,
                          ContextMemoryService contextMemoryService,
                          CoordinatorService coordinatorService,
                          FollowUpService followUpService,
                          AgentService agentService,
                          SkillRegistry skillRegistry,
                          IntentPlannerService intentPlannerService,
                          IntentConflictResolver intentConflictResolver,
                          ReportTaskStore reportTaskStore) {
        this.conversationService = conversationService;
        this.contextMemoryService = contextMemoryService;
        this.coordinatorService = coordinatorService;
        this.followUpService = followUpService;
        this.agentService = agentService;
        this.skillRegistry = skillRegistry;
        this.intentPlannerService = intentPlannerService;
        this.intentConflictResolver = intentConflictResolver;
        this.reportTaskStore = reportTaskStore;
    }

    /**
     * 强制终止当前对话的流式生成
     * 前端点击"停止"按钮时调用，配合前端断开 SSE 连接（AbortController）双保险生效
     */
    @PostMapping("/chat/stop")
    public Mono<Map<String, Object>> stopChat(@RequestBody Map<String, String> body,
                                              @RequestAttribute("currentUser") UserInfo currentUser) {
        String conversationId = body.get("conversationId");
        Map<String, Object> resp = new LinkedHashMap<>();
        if (conversationId == null || conversationId.isBlank()) {
            resp.put("ok", false);
            resp.put("message", "conversationId 不能为空");
            return Mono.just(resp);
        }
        // 仅允许终止当前用户自己的会话
        Map<String, Conversation> userConvs = conversationService.getUserConvs(currentUser.getId());
        if (!userConvs.containsKey(conversationId)) {
            resp.put("ok", false);
            resp.put("message", "会话不存在");
            return Mono.just(resp);
        }
        contextMemoryService.cancel(conversationId);
        log.info("Chat stop requested for conversation: {}", conversationId);
        resp.put("ok", true);
        return Mono.just(resp);
    }

    /**
     * 持久化前端本地生成的卡片消息（如模板选择后的"已选择模板"跳转卡）：
     * 该卡由前端 ReportGenerateCard 点击模板时直接生成（不经协调器，避免 LLM 提取
     * template_id 失败），若不持久化则穿插恢复后切换对话框"原来提供的模板记录"丢失。
     * 按穿插边界规则插入消息流（穿插中保持在恢复确认卡上方），切换会话后前端按原位置渲染恢复。
     */
    @PostMapping("/chat/card")
    public Mono<Map<String, Object>> persistCardMessage(@RequestBody Map<String, Object> body,
                                                        @RequestAttribute("currentUser") UserInfo currentUser) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String conversationId = body.get("conversationId") == null ? "" : String.valueOf(body.get("conversationId"));
        if (conversationId.isBlank()) {
            resp.put("ok", false);
            resp.put("message", "conversationId 不能为空");
            return Mono.just(resp);
        }
        // 仅允许操作当前用户自己的会话
        Map<String, Conversation> userConvs = conversationService.getUserConvs(currentUser.getId());
        if (!userConvs.containsKey(conversationId)) {
            resp.put("ok", false);
            resp.put("message", "会话不存在");
            return Mono.just(resp);
        }
        Object msgObj = body.get("message");
        if (!(msgObj instanceof Map<?, ?>)) {
            resp.put("ok", false);
            resp.put("message", "message 不能为空");
            return Mono.just(resp);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) msgObj;
        String msgId = msg.get("id") == null ? UUID.randomUUID().toString() : String.valueOf(msg.get("id"));
        Conversation conv = userConvs.get(conversationId);
        // 幂等：同 id 已存在（前端网络重试/重复点击同一模板）时不重复插入
        for (Message m : conv.getMessages()) {
            if (msgId.equals(m.getId())) {
                resp.put("ok", true);
                resp.put("status", "exists");
                return Mono.just(resp);
            }
        }
        Object extraObj = msg.get("extra");
        Map<String, Object> extra = extraObj instanceof Map<?, ?>
                ? (Map<String, Object>) extraObj : new LinkedHashMap<>();
        Message card = new Message(msgId, "assistant", "", Instant.now().toString());
        card.setExtra(extra);
        // 按穿插边界规则定位插入（与后端 result 分支持久化一致）：
        // 穿插中结果卡保持在恢复确认卡上方，避免切换会话后跑到穿插对话之后位置错乱
        insertBeforeBoundaryCard(conv.getMessages(), card);
        conv.setUpdatedAt(card.getCreatedAt());
        conversationService.persist();
        resp.put("ok", true);
        return Mono.just(resp);
    }

    /**
     * 报告生成完成通知（前端进度卡轮询到 completed/failed 后调用）：
     * 将处于 WAITING_EXTERNAL 的 generate_report 步骤标记 DONE/FAILED 并完成规划收尾准备：
     * - 还有下一步 → 设置 planConfirming 并返回 next（含确认卡片数据，前端据此插入确认卡片），
     *   用户点击"继续"后走 chatStream 的 planConfirming 分支推进下一步；
     * - 已是最后一步 → 关闭规划并返回 finished（含汇总文案，前端展示"全部任务已完成"）。
     * 非等待外部状态的请求返回 ignored（幂等，重复轮询/并发通知安全）。
     */
    @PostMapping("/plan/report-complete")
    public Mono<Map<String, Object>> reportComplete(@RequestBody Map<String, String> body,
                                                    @RequestAttribute("currentUser") UserInfo currentUser) {
        String conversationId = body.get("conversationId");
        String reportId = body.get("reportId");
        String status = body.getOrDefault("status", "");
        Map<String, Object> resp = new LinkedHashMap<>();
        if (conversationId == null || conversationId.isBlank()) {
            resp.put("ok", false);
            resp.put("message", "conversationId 不能为空");
            return Mono.just(resp);
        }
        // 仅允许操作当前用户自己的会话
        Map<String, Conversation> userConvs = conversationService.getUserConvs(currentUser.getId());
        if (!userConvs.containsKey(conversationId)) {
            resp.put("ok", false);
            resp.put("message", "会话不存在");
            return Mono.just(resp);
        }
        // 报告任务归属校验：reportId 关联的任务必须属于该会话（H5 直接发起、无会话归属的任务除外），
        // 防止前端轮询回调晚到（用户已切换会话）时用错误会话推进规划、把本会话收尾写入其他会话
        if (reportId != null && !reportId.isEmpty()) {
            ReportTaskStore.ReportTask task = reportTaskStore.getTask(reportId);
            if (task != null && !task.getConversationId().isEmpty()
                    && !conversationId.equals(task.getConversationId())) {
                resp.put("ok", true);
                resp.put("status", "ignored");
                return Mono.just(resp);
            }
        }
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
        ContextMemoryService.PlanStep cur = ctx == null ? null
                : contextMemoryService.getCurrentPlanStep(conversationId);
        // 穿插挂起期间规划未激活（suspendPlan 已把 pendingPlan 移到 suspendedPlan 快照），
        // getCurrentPlanStep 返回 null——从挂起快照中找报告步骤穿透标记状态；收尾（确认下一步/
        // 关闭规划）留待用户确认恢复挂起规划后进行，避免穿插流程中被确认卡片干扰
        if (cur == null && ctx != null && contextMemoryService.hasSuspendedPlan(conversationId)) {
            int spIdx = ctx.suspendedIndex;
            if (spIdx >= 0 && spIdx < ctx.suspendedPlan.size()) {
                ContextMemoryService.PlanStep sp = ctx.suspendedPlan.get(spIdx);
                if ("generate_report".equals(sp.skill)
                        && sp.status == ContextMemoryService.PlanStatus.WAITING_EXTERNAL) {
                    boolean spCompleted = "completed".equalsIgnoreCase(status);
                    sp.status = spCompleted ? ContextMemoryService.PlanStatus.DONE
                            : ContextMemoryService.PlanStatus.FAILED;
                    sp.summary = spCompleted ? "生成尽调报告（完成）" : "生成尽调报告（失败）";
                    if (reportId != null && !reportId.isEmpty()) {
                        sp.params.put("report_id", reportId);
                    }
                    log.info("Report {} for suspended conversation {} {} (marked in suspended snapshot)",
                            reportId, conversationId, spCompleted ? "completed" : "failed");
                    resp.put("ok", true);
                    resp.put("status", "marked");
                    return Mono.just(resp);
                }
            }
        }
        // 仅当当前步骤为 generate_report 且处于等待外部完成状态时才推进，其余幂等忽略
        // （如单技能模式无规划、报告已在别处收尾、或重复通知）
        if (cur == null || !"generate_report".equals(cur.skill)
                || cur.status != ContextMemoryService.PlanStatus.WAITING_EXTERNAL) {
            resp.put("ok", true);
            resp.put("status", "ignored");
            return Mono.just(resp);
        }
        boolean completed = "completed".equalsIgnoreCase(status);
        cur.status = completed ? ContextMemoryService.PlanStatus.DONE
                : ContextMemoryService.PlanStatus.FAILED;
        cur.summary = completed ? "生成尽调报告（完成）" : "生成尽调报告（失败）";
        if (reportId != null && !reportId.isEmpty()) {
            cur.params.put("report_id", reportId);
        }
        log.info("Report {} for conversation {} {}, finalizing plan step", reportId, conversationId,
                completed ? "completed" : "failed");

        int nextIdx = ctx.planIndex + 1;
        if (nextIdx < ctx.pendingPlan.size()) {
            // 还有下一步 → 设置确认标记，返回确认卡片数据（与 SSE plan_step_confirm 同结构，前端本地渲染）
            ContextMemoryService.PlanStep next = ctx.pendingPlan.get(nextIdx);
            contextMemoryService.setPlanConfirming(conversationId, true);
            int total = ctx.pendingPlan.size();
            int doneIdx = ctx.planIndex + 1;
            String nextDesc = describePlanStep(next);
            // 携带规划状态快照（当前步 DONE + confirming=true），供前端直接更新规划面板
            resp.put("plan", contextMemoryService.getPlanStatusData(conversationId));
            // 持久化收尾快照：reportComplete 的本地注入不落库，切换会话/刷新后消息流若仍只有
            // 过时的"执行中"快照，面板会永久停留在执行中态——按 planId 原地 upsert 终态快照，
            // 恢复渲染时面板与后端状态一致
            persistPlanCardEvent(userConvs.get(conversationId),
                    planSnapshotToEventJson((Map<String, Object>) resp.get("plan")));
            resp.put("ok", true);
            resp.put("status", "next");
            resp.put("text", "第 " + doneIdx + "/" + total + " 步已完成，是否继续执行第 " + (doneIdx + 1)
                    + "/" + total + " 步（" + nextDesc + "）？");
            resp.put("current_step", doneIdx);
            resp.put("total_steps", total);
            resp.put("next_step", nextDesc);
            return Mono.just(resp);
        }
        // 最后一步 → 关闭规划，返回汇总文案（前端展示"全部任务已完成"）
        String summaryText = intentPlannerService.buildPlanSummary(ctx);
        // 终态快照（全部步骤 DONE）须在 clearPendingPlan 之前取，供前端规划面板刷新完成态
        resp.put("plan", contextMemoryService.getPlanStatusData(conversationId));
        // 持久化终态快照（同上：不落库则切换/刷新后面板停留在过时的"执行中"快照）
        persistPlanCardEvent(userConvs.get(conversationId),
                planSnapshotToEventJson((Map<String, Object>) resp.get("plan")));
        contextMemoryService.clearPendingPlan(conversationId);
        resp.put("ok", true);
        resp.put("status", "finished");
        resp.put("text", summaryText);
        return Mono.just(resp);
    }

    /**
     * 查询会话当前任务规划状态（切换会话/页面刷新时前端恢复规划面板）：
     * 返回与 plan_status 事件 data 同结构的快照：steps/index/active/confirming/suspended/planId。
     */
    @GetMapping("/plan/{conversationId}/status")
    public Mono<Map<String, Object>> planStatus(@PathVariable String conversationId,
                                                @RequestAttribute("currentUser") UserInfo currentUser) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Map<String, Conversation> userConvs = conversationService.getUserConvs(currentUser.getId());
        if (!userConvs.containsKey(conversationId)) {
            resp.put("ok", false);
            resp.put("message", "会话不存在");
            return Mono.just(resp);
        }
        resp.put("ok", true);
        resp.put("plan", contextMemoryService.getPlanStatusData(conversationId));
        return Mono.just(resp);
    }

    /**
     * 生成规划步骤显示名（chat → 对话问答；其余 → 技能名（主体）），与 IntentPlannerService 展示逻辑一致。
     */
    private String describePlanStep(ContextMemoryService.PlanStep step) {
        boolean isChat = "chat".equals(step.skill);
        String displayName = isChat ? "对话问答" : step.skill;
        Object company = step.params.get("company_name");
        String companyStr = company == null ? "" : String.valueOf(company);
        if (!isChat && companyStr != null && !companyStr.isBlank()) {
            displayName += "（" + companyStr + "）";
        }
        return displayName;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestBody ChatRequest body,
            @RequestAttribute("currentUser") UserInfo currentUser) {

        System.out.println("===== 请求到达 ChatController！消息: " + body.getMessage());
        System.out.println("===== 当前用户: " + currentUser.getId());

        String userId = currentUser.getId();
        Map<String, Conversation> userConvs = conversationService.getUserConvs(userId);

        // 获取或创建会话（同步快速）
        String conversationId = body.getConversationId();
        Conversation conv;
        if (conversationId == null || !userConvs.containsKey(conversationId)) {
            conv = conversationService.createConversation(userId, "新对话");
            conversationId = conv.getId();
        } else {
            conv = userConvs.get(conversationId);
        }

        // 存储用户消息
        String userMsgId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        // 附件信息：汇总到消息内容中传递给 LLM
        List<Map<String, Object>> attachments = body.getAttachments();
        String enhancedMessage = body.getMessage();
        if (attachments != null && !attachments.isEmpty()) {
            StringBuilder sb = new StringBuilder(body.getMessage());
            sb.append("\n\n[用户上传了以下附件：");
            for (int i = 0; i < attachments.size(); i++) {
                Map<String, Object> att = attachments.get(i);
                String name = (String) att.getOrDefault("name", "未知文件");
                sb.append(i > 0 ? "、" : "").append(name);
                // 保留 url 等信息供前端展示
                att.put("id", "att-" + userMsgId + "-" + i);
            }
            sb.append("]");
            enhancedMessage = sb.toString();
        }

        // 保存附件 URL 到会话上下文，供技能通过 _attachment_url 参数使用（如营业执照信息核实）
        if (attachments != null && !attachments.isEmpty()) {
            Object firstUrl = attachments.get(0).get("url");
            if (firstUrl instanceof String s && !s.isEmpty()) {
                contextMemoryService.updateAttachment(conversationId, s);
                log.info("Attachment URL saved to context: {}", s);
            }
        }

        Message userMsg = new Message(userMsgId, "user", body.getMessage(), now);
        userMsg.setAttachments(attachments);
        // 按前端实时插入规则定位（useChat.ts insertInterleavingAware）：确认动作/穿插恢复文本回复
        // 追加在卡片之后（消费该卡片）；穿插中（未消费 resume_confirm）新消息插到恢复卡之前；
        // 其余消息移除失效的未消费"下一步"确认卡后追加（穿插挂起时旧确认卡实时流中会被移除）。
        // 否则切换会话后残留的确认卡/穿插对话位置与实时显示不一致
        String rawUserMsg = body.getMessage();
        if (isConfirmAction(rawUserMsg)) {
            conv.getMessages().add(userMsg);
        } else if (lastUnconsumedCardIndex(conv.getMessages(), "resume_confirm") >= 0
                && isResumeReplyText(rawUserMsg)) {
            conv.getMessages().add(userMsg);
        } else {
            insertInterleavingAware(conv.getMessages(), userMsg);
        }
        conv.setUpdatedAt(now);

        // 首次消息设置标题
        if (conv.getMessages().size() == 1) {
            conv.setCreatedAt(now);
            String title = body.getMessage();
            conv.setTitle(title.length() > 30 ? title.substring(0, 30) + "..." : title);
        }

        final String convId = conversationId;
        final Conversation finalConv = conv;
        final String finalMessage = enhancedMessage;

        // 每次新消息开始时重置该会话的终止标记（强制终止后允许继续对话）
        contextMemoryService.clearCancelled(convId);

        // 检查会话状态：待确认澄清（pendingClarification）优先于一切，其次任务规划（pendingPlan），最后待处理技能（pendingSkill）
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        boolean hasPendingClarification = ctx.pendingClarification != null && !ctx.pendingClarification.isEmpty();
        boolean hasPendingPlan = ctx.planActive && !ctx.pendingPlan.isEmpty();
        boolean hasPendingSkill = ctx.hasPendingSkill();
        ContextMemoryService.PlanStep currentPlanStep =
                hasPendingPlan ? contextMemoryService.getCurrentPlanStep(convId) : null;
        boolean planNeedsInput = currentPlanStep != null && currentPlanStep.needsInput;
        // 当前步骤等待外部异步完成（如 generate_report 在 H5 编辑页面异步生成报告）：
        // 期间用户输入一律拦截（不推进规划、不重跑步骤），收尾由前端轮询到报告生成完成后
        // 调用 /api/plan/report-complete 通知后端标记结束
        boolean planWaitingExternal = currentPlanStep != null
                && currentPlanStep.status == ContextMemoryService.PlanStatus.WAITING_EXTERNAL;

        // 初始事件：根据状态选择更准确的提示文案
        String thinkingText;
        if (hasPendingClarification) {
            thinkingText = "正在处理您的选择...";
        } else if (ctx.planConfirming) {
            thinkingText = "正在处理您的确认...";
        } else if (ctx.resumeConfirming) {
            thinkingText = "正在处理您的选择...";
        } else if (hasPendingPlan) {
            thinkingText = "正在执行任务，请稍候...";
        } else if (hasPendingSkill) {
            thinkingText = "正在查询，请稍候...";
        } else {
            thinkingText = "正在分析您的问题...";
        }
        Flux<String> initEvent = Flux.just(sseEvent("thinking",
                Map.of("content", thinkingText), null, convId));

        // 主流程
        Flux<String> mainFlow;
        // 6.5 模糊匹配候选「以上选项均不是」统一拦截：所有出候选列表的公司模糊匹配技能
        //     （历史尽调报告 candidates / 企业查询、风险预查、信息核实的 ambiguous/not_found）
        //     点击该选项后发送本固定短语，直接返回引导提示：
        //     - 规划步骤等待输入 → 本步视为结束（跳过）并追加步骤确认卡；
        //     - 非规划模式（意图穿插/单技能，上下文在 pendingSkill）→ 消费并清除待处理技能，
        //       穿插场景由主流程统一 resumePlanIfSuspended 返回"穿插任务已完成"确认卡；
        //     用户下一条消息不再回到原技能上下文，可提供准确企业名称或信用代码重试
        if (NONE_OF_ABOVE_PATTERN.matcher(finalMessage.trim()).matches()) {
            log.info("User selected none-of-the-above for fuzzy match candidates, guiding re-entry: {}", finalMessage);
            // 拦截分支不经过 handleChat/handleSingleSkill 的统一保存逻辑，需手动将引导回复写入会话历史，
            // 否则切换会话后该条回复不可见（用户消息已在上面保存，此回复对应用户交互必须一起持久化）
            Message guideMsg = new Message(UUID.randomUUID().toString(), "assistant",
                    NONE_OF_ABOVE_REPLY, Instant.now().toString());
            // 与前端占位消息位置一致：穿插中保持在恢复确认卡上方
            insertBeforeBoundaryCard(conv.getMessages(), guideMsg);
            conv.setUpdatedAt(guideMsg.getCreatedAt());
            Flux<String> guideFlow = Flux.just(
                    sseEvent("text_delta", Map.of("content", NONE_OF_ABOVE_REPLY), null, convId),
                    sseEvent("text_done", Map.of("content", NONE_OF_ABOVE_REPLY), null, convId)
            );
            // 规划步骤等待模糊匹配输入时，用户明确表示候选均不对 → 提示语后本步视为结束（跳过），
            // 追加"是否继续执行下一步"确认卡；确认卡下用户可点"继续"推进下一步，或直接输入
            // 准确企业名称/信用代码（走 handlePlanConfirmReply 其他输入分支挂起-路由）重试当前步骤
            if (hasPendingPlan && planNeedsInput) {
                int stepNo = ctx.planIndex + 1;
                int totalSteps = ctx.pendingPlan.size();
                String note = "以上选项均不是您要查询的企业，第 " + stepNo + "/" + totalSteps + " 步未匹配到企业已跳过";
                mainFlow = guideFlow.concatWith(
                        intentPlannerService.stepDoneAndConfirm(convId, planInvoker(convId, userId, finalConv), note));
            } else {
                // 非规划模式的模糊匹配等待（意图穿插/单技能，上下文暂存在 pendingSkill）：
                // 点击"以上选项均不是"视为该穿插意图结束——清除待处理技能上下文，引导文本后
                // 由主流程统一 resumePlanIfSuspended 检测挂起的旧规划，返回"穿插任务已完成"
                // 确认卡。不清理的话 pendingSkill 占用使 resumePlanIfSuspended 提前跳过
                // （hasPendingSkill() 为 true），且用户下一条消息仍被分支 5 拦截重跑技能，
                // 反复返回候选列表死循环
                if (hasPendingSkill) {
                    log.info("None-of-the-above ends pending skill {} (interleaved/single skill fuzzy match)",
                            ctx.pendingSkillName);
                    contextMemoryService.clearPendingSkill(convId);
                    contextMemoryService.clearAttachment(convId);
                    ctx.pendingSkillRetry = 0;
                }
                mainFlow = guideFlow;
            }
        } else if (hasPendingClarification) {
            // 1. 用户回复了澄清选项 → 取走澄清上下文，解析用户输入为参数，直接执行技能（不重新走 LLM，最高优先级）
            log.info("Pending clarification reply received: {}", finalMessage);
            mainFlow = handleClarificationReply(convId, userId, finalConv, finalMessage);
        } else if (ctx.planConfirming) {
            // 2. 步骤完成后等待用户确认是否继续下一步（步骤间确认）→ 解析确认/停止指令
            log.info("Plan step confirmation reply received: {}", finalMessage);
            mainFlow = handlePlanConfirmReply(convId, userId, finalConv, finalMessage);
        } else if (ctx.resumeConfirming) {
            // 2.5 穿插的新意图已完成，等待用户确认是否回到穿插前那一步（resume_confirm 卡片）
            //     → 解析恢复/拒绝指令（其他输入视为新意图，丢弃挂起规划后正常路由）
            log.info("Resume confirm reply received: {}", finalMessage);
            mainFlow = handleResumeConfirmReply(convId, userId, finalConv, finalMessage);
        } else if (planWaitingExternal) {
            // 2.6 报告生成步骤等待外部异步完成：报告在 H5 编辑页面异步生成，聊天中的输入无法服务
            //     该步骤——用户穿插其他意图（如"帮我查下小米风险"）不应被"正在生成中"提示拦截，
            //     挂起当前规划优先执行穿插意图（与分支 3/4 一致）；报告生成完成的收尾由前端进度卡
            //     轮询调 /api/plan/report-complete（穿插期间穿透挂起快照标记，恢复后按状态分支继续）
            if (intentPlannerService.isInterleavingIntent(currentPlanStep.skill, finalMessage)) {
                log.info("Interleaving intent while plan step waits external, suspending plan: {}", finalMessage);
                contextMemoryService.suspendPlan(convId);
                // 挂起旧规划 → 先发状态快照（active=false, suspended=true，前端面板切挂起态）再路由新意图
                mainFlow = Flux.concat(
                        Flux.just(intentPlannerService.planStatusEvent(convId)),
                        coordinatorService.routeIntent(finalMessage, finalConv.getMessages())
                                .flatMapMany(decision -> dispatchDecision(decision, convId, userId, finalConv, finalMessage)));
            } else {
                // 非穿插输入（无技能命中/与报告步骤无关的闲聊）→ 拦截提示等待，不推进规划
                log.info("Plan step {} waiting external report generation, ignoring input: {}",
                        currentPlanStep == null ? "?" : currentPlanStep.skill, finalMessage);
                String waitText = "尽调报告正在生成中，请稍候…";
                // 与 6.5 拦截分支一致：拦截回复不经过统一保存逻辑，手动写入会话历史，切换会话后仍可见
                Message waitMsg = new Message(UUID.randomUUID().toString(), "assistant",
                        waitText, Instant.now().toString());
                // 与前端占位消息位置一致：穿插中保持在恢复确认卡上方
                insertBeforeBoundaryCard(conv.getMessages(), waitMsg);
                conv.setUpdatedAt(waitMsg.getCreatedAt());
                mainFlow = Flux.just(
                        sseEvent("text_delta", Map.of("content", waitText), null, convId),
                        sseEvent("text_done", Map.of("content", waitText), null, convId)
                );
            }
        } else if (hasPendingPlan && planNeedsInput) {
            // 3. 规划中当前步骤等待模糊匹配选择（needsInput）：先做意图穿插检测——用户提出
            //    其他技能的新意图（如"顺便查下华为的融资"）→ 挂起当前规划，优先处理新意图，
            //    完成后断点再续；否则视为对当前步骤的参数补充，合并输入后重跑当前步骤
            //    （不重新走 LLM）。needsInput 状态下点击候选选项/补充企业名都是对当前步骤
            //    询问的回复，不算意图穿插（suppressSkillHit=true，仅显式穿插标记仍视为穿插）。
            //    例外：等附件类步骤（verify_business_license 已解析出信用代码、仅缺上传的
            //    营业执照）——文本输入永远不可能是对该步骤的有效回复（附件只能通过上传获得），
            //    无技能命中的文本合并重跑后技能仍返回"请上传营业执照"，穿插意图一直不被执行
            //    （死循环）；故等附件时恢复技能关键词穿插检测（suppressSkillHit=false），
            //    命中其他技能即视为穿插，优先执行后经 resume_confirm 回到该步骤继续等待附件
            boolean waitingAttachment = "verify_business_license".equals(currentPlanStep.skill)
                    && currentPlanStep.params.get("credit_code") != null
                    && !String.valueOf(currentPlanStep.params.get("credit_code")).trim().isEmpty();
            if (intentPlannerService.isInterleavingIntent(currentPlanStep.skill, finalMessage, !waitingAttachment)) {
                log.info("Interleaving intent while plan step waits input, suspending plan: {}", finalMessage);
                contextMemoryService.suspendPlan(convId);
                // 挂起旧规划 → 先发状态快照（active=false, suspended=true，前端面板切挂起态）再路由新意图
                mainFlow = Flux.concat(
                        Flux.just(intentPlannerService.planStatusEvent(convId)),
                        coordinatorService.routeIntent(finalMessage, finalConv.getMessages())
                                .flatMapMany(decision -> dispatchDecision(decision, convId, userId, finalConv, finalMessage)));
            } else {
                log.info("Plan step needs input, merging user input: {}", finalMessage);
                intentPlannerService.mergeUserInput(convId, currentPlanStep, finalMessage);
                mainFlow = intentPlannerService.runPlan(convId, planInvoker(convId, userId, finalConv));
            }
        } else if (hasPendingPlan) {
            // 4. 规划激活但当前步骤无输入等待：输入可能是穿插的新意图（→ 挂起规划优先处理），
            //    否则视为步骤疑似已完成，发确认卡片或收尾（正常流程由技能结果链式触发）
            if (intentPlannerService.isInterleavingIntent(currentPlanStep.skill, finalMessage)) {
                log.info("Interleaving intent during plan execution, suspending plan: {}", finalMessage);
                contextMemoryService.suspendPlan(convId);
                // 挂起旧规划 → 先发状态快照（active=false, suspended=true，前端面板切挂起态）再路由新意图
                mainFlow = Flux.concat(
                        Flux.just(intentPlannerService.planStatusEvent(convId)),
                        coordinatorService.routeIntent(finalMessage, finalConv.getMessages())
                                .flatMapMany(decision -> dispatchDecision(decision, convId, userId, finalConv, finalMessage)));
            } else {
                log.info("Plan active with no pending input, finalizing current step for confirmation");
                mainFlow = intentPlannerService.stepDoneAndConfirm(convId, planInvoker(convId, userId, finalConv));
            }
        } else if (hasPendingSkill) {
            // 5. 待处理技能 → 现有逻辑不动
            // 检查重试上限：防止 pending skill 死循环（如参数丢失导致永不到达 result）
            if (ctx.pendingSkillRetry >= 3) {
                log.warn("Pending skill {} exceeded retry limit, clearing and falling back to Coordinator",
                        ctx.pendingSkillName);
                contextMemoryService.clearPendingSkill(convId);
                mainFlow = coordinatorService.routeIntent(finalMessage, finalConv.getMessages())
                        .flatMapMany(decision -> dispatchDecision(decision, convId, userId, finalConv, finalMessage));
            } else {
                ctx.pendingSkillRetry++;
                log.info("Pending skill {} retry {}/3: {}", ctx.pendingSkillName, ctx.pendingSkillRetry, finalMessage);
                // 有待处理技能 → 直接路由，跳过 Coordinator/LLM 意图识别
                String pendingSkill = ctx.pendingSkillName;
                Map<String, Object> pendingParams = new LinkedHashMap<>(ctx.pendingSkillParams);
                pendingParams.put("_user_input", finalMessage);
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("action", "skill");
                decision.put("skill", pendingSkill);
                decision.put("params", pendingParams);
                decision.put("reason", "继续待处理技能: " + pendingSkill);
                contextMemoryService.clearPendingSkill(convId);
                log.info("Routing to pending skill: {}, user_input: {}", pendingSkill, finalMessage);
                mainFlow = handleSingleSkill(decision, convId, userId, finalConv);
            }
        } else if (body.getMessage() == null || body.getMessage().trim().isEmpty()) {
            // 5. 仅上传附件、无文字输入：跳过意图识别，直接走对话助手
            // 由 Agent 按 system prompt 规则主动询问附件用途（信息核实 / 生成尽调报告）
            log.info("Empty message with attachments, routing directly to chat for purpose inquiry");
            mainFlow = handleChat(convId, finalConv, finalMessage);
        } else {
            // 6. 无待处理状态 → 走 Coordinator 意图识别（携带对话历史以便 LLM 理解上下文）
            mainFlow = coordinatorService.routeIntent(finalMessage, finalConv.getMessages())
                    .flatMapMany(decision -> {
                        String action = (String) decision.getOrDefault("action", "");
                        if ("multi_skill".equals(action)) {
                            // 多意图决策 → 统一走规划模式串行执行：
                            // 技能步骤等待用户输入时不推进后续意图；只有当前技能真正结束才执行下一个
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> intents =
                                    (List<Map<String, Object>>) decision.getOrDefault("intents", List.of());
                            return planMultiSkill(intents, convId, userId, finalConv, finalMessage);
                        }
                        return dispatchDecision(decision, convId, userId, finalConv, finalMessage);
                    });
        }

        return initEvent.concatWith(mainFlow)
                // 意图穿插恢复询问：新意图（单技能/chat/澄清/pendingSkill）处理完成后，若存在挂起的
                // 旧规划则发 resume_confirm 确认卡片，询问用户是否回到穿插前那一步（不自动恢复；
                // 嵌套规划完成时在 stepDoneAndConfirm 内即时触发，此处作为统一兜底）
                .concatWith(Flux.defer(() -> intentPlannerService.resumePlanIfSuspended(convId,
                        planInvoker(convId, userId, finalConv))))
                // 强制终止检查：一旦该会话被标记为取消，立即截断剩余事件流
                .takeWhile(e -> !contextMemoryService.isCancelled(convId))
                // 所有事件流结束后发送 done 事件
                .concatWith(Flux.just(sseEvent("done", Map.of("conversation_id", convId), null, convId)))
                // 统一拦截持久化任务规划相关卡片事件（切换会话后卡片按原位置恢复，不消失）：
                // plan_status 按 planId 原地 upsert 面板，确认卡/进度气泡按事件顺序追加；
                // 覆盖拦截分支/意图穿插挂起快照等所有路径，且顺序与前端接收一致
                .doOnNext(event -> persistPlanCardEvent(finalConv, event))
                .doOnSubscribe(s -> System.out.println("🔵 SSE Flux 被订阅!"))
                //.doOnNext(event -> System.out.println("📤 发送 SSE: " + event.substring(0, Math.min(120, event.length()))))
                .doOnComplete(() -> {
                    System.out.println("✅ SSE Flux 完成");
                    contextMemoryService.clearCancelled(convId);
                })
                .doOnCancel(() -> {
                    System.out.println("⏹️ SSE Flux 被取消（前端断开连接）");
                    contextMemoryService.clearCancelled(convId);
                })
                .doOnError(e -> log.error("Stream error", e))
                .onErrorResume(e -> Flux.just(sseEvent("error",
                        Map.of("content", "处理请求失败: " + e.getMessage()), null, null)));
    }

    /**
     * 决策分发：multi_skill → 串行多技能；skill → 单技能；clarification → 澄清；其他 → 聊天兜底
     */
    private Flux<String> dispatchDecision(Map<String, Object> decision, String convId,
                                          String userId, Conversation conv, String userMessage) {
        String action = (String) decision.getOrDefault("action", "");
        return switch (action) {
            case "multi_skill" -> {
                // 统一走规划模式串行执行（与 chatStream 拦截路径一致）：
                // 技能等待输入时不推进后续意图，只有当前技能真正结束才执行下一个
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> intents =
                        (List<Map<String, Object>>) decision.getOrDefault("intents", List.of());
                yield planMultiSkill(intents, convId, userId, conv, userMessage);
            }
            case "skill" -> handleSingleSkill(decision, convId, userId, conv);
            case "clarification" -> {
                // LLM 直接产出 clarification（暂不期望，保留解析）：暂存澄清上下文，发澄清事件，本轮结束
                @SuppressWarnings("unchecked")
                Map<String, Object> clarContext = (Map<String, Object>) decision.getOrDefault("context", Map.of());
                if (clarContext.isEmpty()) {
                    log.warn("clarification decision without context, falling back to chat");
                    yield handleChat(convId, conv, userMessage);
                }
                contextMemoryService.setPendingClarification(convId, clarContext);
                log.info("Coordinator returned clarification: {}", decision.getOrDefault("question", ""));
                yield Flux.just(sseEvent("clarification", decision, null, convId));
            }
            default -> handleChat(convId, conv, userMessage);
        };
    }

    /**
     * 处理澄清选项回复（pendingClarification 分支，最高优先级，不重新走 LLM）：
     * 取走澄清上下文 → 解析用户消息为参数（JSON 合并 / 视为 company_name）→ 构造单意图决策直接执行技能。
     */
    @SuppressWarnings("unchecked")
    private Flux<String> handleClarificationReply(String convId, String userId,
                                                  Conversation conv, String userMessage) {
        Map<String, Object> clarContext = contextMemoryService.consumePendingClarification(convId);
        if (clarContext == null || clarContext.isEmpty()) {
            log.warn("Pending clarification flag set but context empty, clearing and falling back to chat");
            return handleChat(convId, conv, userMessage);
        }
        String skill = (String) clarContext.getOrDefault("skill", "");
        Map<String, Object> params = new LinkedHashMap<>(
                (Map<String, Object>) clarContext.getOrDefault("params", Map.of()));

        // 解析用户回复：JSON（选项点击原样发送）→ 合并字段；否则视为公司名/补充输入
        String trimmed = userMessage == null ? "" : userMessage.trim();
        Map<String, Object> replyJson = null;
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                replyJson = mapper.readValue(trimmed, Map.class);
            } catch (Exception e) {
                log.warn("Clarification reply JSON parse failed, treat as company name: {}", e.getMessage());
            }
        }
        // "全部执行"：放行澄清上下文保存的完整 intents 到任务规划（跳过冲突检测，用户已确认）
        if (replyJson != null && "execute_all".equals(replyJson.get("action"))) {
            return executeAllAfterClarification(convId, userId, conv, clarContext, userMessage);
        }
        if (replyJson != null) {
            replyJson.forEach(params::put);
            log.info("Clarification reply merged JSON params: {}", replyJson.keySet());
        } else if (!trimmed.isEmpty()) {
            params.put("company_name", trimmed);
        }
        params.put("_user_input", userMessage);

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "skill");
        decision.put("skill", skill);
        decision.put("params", params);
        decision.put("reason", "澄清选项确认后执行");
        log.info("Executing skill after clarification: {}, params: {}", skill, params);
        return handleSingleSkill(decision, convId, userId, conv);
    }

    /**
     * 处理步骤间确认回复（ctx.planConfirming 分支，步骤真正结束后等待用户决定是否继续下一步）：
     * - 确认卡片"继续"按钮发送 {"action":"plan_continue"} → 推进并执行下一步（唯一入口）
     * - 确认卡片"结束"按钮发送 {"action":"plan_stop"} → 结束规划
     * - 文本"继续/下一步"类回复 → 推进下一步；停止类文本 → 结束规划
     * - 其他任何输入（已结束步骤后的新请求/新话题）→ 结束当前规划，
     *   交给 Coordinator 重新识别意图（重跑已 DONE 的步骤只会重复输出旧结果）
     */
    private Flux<String> handlePlanConfirmReply(String convId, String userId,
                                                Conversation conv, String userMessage) {
        String trimmed = userMessage == null ? "" : userMessage.trim();
        String action = "";
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> replyJson = mapper.readValue(trimmed, Map.class);
                action = String.valueOf(replyJson.getOrDefault("action", ""));
            } catch (Exception e) {
                log.warn("Plan confirm reply JSON parse failed, treat as text: {}", e.getMessage());
            }
        }
        // 明确停止：确认卡片"结束任务"按钮（plan_stop）或停止类文本
        if ("plan_stop".equals(action)
                || trimmed.contains("结束") || trimmed.contains("停止")
                || trimmed.contains("不用") || trimmed.contains("不需要")
                || trimmed.contains("算了") || trimmed.contains("跳过")) {
            log.info("User chose to stop the plan");
            return intentPlannerService.confirmStop(convId, planInvoker(convId, userId, conv));
        }
        // 明确继续：确认卡片"继续执行下一步"按钮（plan_continue）或继续类文本
        if ("plan_continue".equals(action)
                || trimmed.contains("继续") || trimmed.contains("下一步")
                || trimmed.contains("接着执行") || trimmed.contains("接着来")) {
            log.info("User confirmed to continue the plan");
            return intentPlannerService.confirmContinue(convId, planInvoker(convId, userId, conv));
        }
        // 其他输入（已 DONE 步骤之后的新请求/新话题）：挂起当前规划（保留断点），交给 Coordinator
        // 识别为新意图执行；新意图处理完成后由 resumePlanIfSuspended 发 resume_confirm 确认卡片
        // 询问用户是否回到穿插前那一步（不自动恢复）。
        // （旧行为是直接结束规划丢弃断点；意图穿插需求下改为挂起-恢复）
        // 注意：能走到本分支说明当前步骤已真正结束（result/not_found 等），此时重跑已完成的步骤
        // 只会再次返回同样结果（如 generate_report 的模板卡片），造成"点完卡片输入新请求却一直
        // 输出第一步内容"的死循环；而第一步流程内的卡片点击（candidates/info_needed，needsInput=true）
        // 走 chatStream 分支 3 合并输入重跑，不经过本方法，因此此处可安全视为"用户发起了新请求"。
        log.info("Plan confirm stage got non-confirm input '{}', suspending plan and re-routing intent", trimmed);
        contextMemoryService.suspendPlan(convId);
        // 挂起旧规划 → 先发状态快照（active=false, suspended=true，前端面板切挂起态）再路由新意图
        return Flux.concat(
                Flux.just(intentPlannerService.planStatusEvent(convId)),
                coordinatorService.routeIntent(trimmed, conv.getMessages())
                        .flatMapMany(decision -> dispatchDecision(decision, convId, userId, conv, trimmed)));
    }

    /**
     * 处理穿插恢复确认回复（ctx.resumeConfirming 分支，穿插的新意图完成后等待用户决定是否回到
     * 穿插进来前的那一步）：
     * - 确认卡片"回到之前的任务"按钮发送 {"action":"plan_resume_yes"} → 恢复挂起规划继续
     * - 确认卡片"不需要"按钮发送 {"action":"plan_resume_no"} → 丢弃挂起规划，旧任务结束
     * - 文本"回到/恢复"类回复 → 恢复；否定类文本 → 丢弃
     * - 其他任何输入 → 视为新意图继续穿插：保留挂起规划（不恢复也不丢弃），按新意图正常路由，
     *   处理完成后 resumePlanIfSuspended 会再次发送确认卡片询问（连续穿插时卡片始终在对话最底部）
     */
    private Flux<String> handleResumeConfirmReply(String convId, String userId,
                                                  Conversation conv, String userMessage) {
        String trimmed = userMessage == null ? "" : userMessage.trim();
        String action = "";
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> replyJson = mapper.readValue(trimmed, Map.class);
                action = String.valueOf(replyJson.getOrDefault("action", ""));
            } catch (Exception e) {
                log.warn("Resume confirm reply JSON parse failed, treat as text: {}", e.getMessage());
            }
        }
        // 明确不恢复：确认卡片"不需要"按钮（plan_resume_no）或否定类文本
        if ("plan_resume_no".equals(action)
                || trimmed.contains("不用") || trimmed.contains("不需要")
                || trimmed.contains("算了") || trimmed.contains("不了")
                || trimmed.contains("不必") || trimmed.contains("不要")) {
            log.info("User chose not to resume suspended plan");
            return intentPlannerService.rejectResume(convId);
        }
        // 明确恢复：确认卡片"回到之前的任务"按钮（plan_resume_yes）或肯定类文本
        if ("plan_resume_yes".equals(action)
                || trimmed.contains("回到") || trimmed.contains("回去")
                || trimmed.contains("恢复") || trimmed.contains("继续之前的")
                || trimmed.contains("接着之前") || trimmed.equals("要") || trimmed.equals("是")) {
            log.info("User confirmed to resume suspended plan");
            return intentPlannerService.confirmResume(convId, userId, planInvoker(convId, userId, conv));
        }
        // 其他输入：保留挂起规划继续穿插（不恢复也不丢弃），按新意图正常路由；
        // 新意图处理完成后 resumePlanIfSuspended 会再次发送确认卡片询问是否回到穿插前那一步
        log.info("Resume confirm got non-answer input '{}', keeping suspended plan and routing intent", trimmed);
        contextMemoryService.setResumeConfirming(convId, false);
        return coordinatorService.routeIntent(trimmed, conv.getMessages())
                .flatMapMany(decision -> dispatchDecision(decision, convId, userId, conv, trimmed));
    }

    /**
     * 澄清"全部执行"分支：将澄清上下文保存的完整 intents 放行到任务规划，
     * 跳过冲突检测（用户已确认全部执行），多个主体各自成步骤串行执行。
     */
    @SuppressWarnings("unchecked")
    private Flux<String> executeAllAfterClarification(String convId, String userId,
                                                      Conversation conv,
                                                      Map<String, Object> clarContext,
                                                      String userMessage) {
        Object rawIntents = clarContext.get("intents");
        List<Map<String, Object>> intents = new ArrayList<>();
        if (rawIntents instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    intents.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        if (intents.isEmpty()) {
            log.warn("Clarification execute_all but no valid intents, falling back to chat");
            return handleChat(convId, conv, userMessage == null ? "" : userMessage);
        }
        ContextMemoryService.ConversationContext planCtx = contextMemoryService.get(convId);
        List<ContextMemoryService.PlanStep> steps = intentPlannerService.buildPlan(intents, planCtx);
        if (steps.isEmpty()) {
            log.warn("Clarification execute_all produced no plan steps, falling back to chat");
            return handleChat(convId, conv, userMessage == null ? "" : userMessage);
        }
        contextMemoryService.setPendingPlan(convId, steps);
        log.info("Clarification execute_all: plan built with {} steps", steps.size());
        return intentPlannerService.runPlan(convId, planInvoker(convId, userId, conv));
    }

    /**
     * 多意图统一入口（chatStream 拦截路径与 dispatchDecision 共用）：
     * 冲突检测 → 构建规划步骤 → 规划模式串行执行。
     * 串行保证：技能步骤只有真正结束（result/not_found/ambiguous/summary/detail/error）
     * 才链式推进下一个意图；info_needed/candidates 仅标记当前步骤等待用户输入，绝不提前开始后续意图。
     */
    @SuppressWarnings("unchecked")
    private Flux<String> planMultiSkill(List<Map<String, Object>> intents, String convId,
                                        String userId, Conversation conv, String userMessage) {
        if (intents.isEmpty()) {
            log.warn("multi_skill decision without intents, falling back to chat");
            return handleChat(convId, conv, userMessage == null ? "" : userMessage);
        }
        // 冲突检测：同技能不同主体需要用户澄清（Phase 4）
        ContextMemoryService.ConversationContext planCtx = contextMemoryService.get(convId);
        Map<String, Object> conflict = intentConflictResolver.detectAndResolve(intents, planCtx);
        if (conflict != null) {
            Map<String, Object> clarContext =
                    (Map<String, Object>) conflict.getOrDefault("context", Map.of());
            contextMemoryService.setPendingClarification(convId, clarContext);
            log.info("Intent conflict needs clarification: {}", conflict.getOrDefault("question", ""));
            // 发 clarification 事件，本轮结束（不执行任何技能）
            return Flux.just(sseEvent("clarification", conflict, null, convId));
        }
        // 无冲突 → 转为任务规划模式串行执行（可穿插等待用户输入）
        List<ContextMemoryService.PlanStep> steps = intentPlannerService.buildPlan(intents, planCtx);
        if (steps.isEmpty()) {
            log.warn("multi_skill decision produced no valid plan steps, falling back to chat");
            return handleChat(convId, conv, userMessage == null ? "" : userMessage);
        }
        contextMemoryService.setPendingPlan(convId, steps);
        log.info("Plan built with {} steps for conversation {}", steps.size(), convId);
        return intentPlannerService.runPlan(convId, planInvoker(convId, userId, conv));
    }

    /**
     * 处理单技能分支（非阻塞）。
     * 支持规划模式（ctx.planActive）：
     * - info_needed/candidates：不设 pendingSkill，改为标记当前规划步骤等待用户输入
     * - result/not_found：当前步骤标 DONE 后链式推进下一步（advanceAndContinue）
     * 非规划模式行为与旧 handleSkill 完全一致。
     */
    private Flux<String> handleSingleSkill(Map<String, Object> decision, String convId,
                                           String userId, Conversation conv) {
        String skillName = (String) decision.getOrDefault("skill", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> skillParams = new LinkedHashMap<>(
                (Map<String, Object>) decision.getOrDefault("params", Map.of()));

        // 上下文记忆补全（仅非规划模式）：规划步骤的主体由 buildPlan/mergeUserInput/broadcastParams 管理，
        // 若规划中再用 ctx 补全，会把其他步骤的主体记忆（如第一步小米）串扰进当前步骤（如第二步华为），
        // 导致"查小米风险再查华为"时第二步仍查小米；非规划模式保持旧行为（"查下风险"用 ctx 记忆补全）。
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        // 历史尽调报告查询/信息核实/风险识别跳过 ctx 补全（与规划模式 buildPlan 跳过预补全一致）：
        // 查询/核实/风险识别主体必须由用户显式提供，ctx 记忆主体直接补全会导致"查询历史尽调报告"
        // /"信息核实"/"风险识别"（无主体）按旧主体直接查询而误报"未查询到报告"/"未找到匹配企业"，
        // 应退回技能层询问主体。
        if (!ctx.planActive && !ctx.isEmpty()
                && !"query_due_diligence_reports".equals(skillName)
                && !"verify_business_license".equals(skillName)
                && !"check_company_risk".equals(skillName)) {
            if (!skillParams.containsKey("company_name") && !skillParams.containsKey("credit_code")) {
                if (ctx.creditCode != null && !ctx.creditCode.isEmpty()) {
                    skillParams.put("credit_code", ctx.creditCode);
                    if (ctx.companyName != null && !ctx.companyName.isEmpty()) {
                        skillParams.put("company_name", ctx.companyName);
                    }
                    log.info("Auto-filled credit_code: {}, company_name: {}", ctx.creditCode, ctx.companyName);
                } else if (ctx.companyName != null && !ctx.companyName.isEmpty()) {
                    skillParams.put("company_name", ctx.companyName);
                    log.info("Auto-filled company_name: {}", ctx.companyName);
                }
            }
        }

        // 注入最新上传的附件 URL（如有），供技能解析营业执照等附件
        if (ctx.attachmentUrl != null && !ctx.attachmentUrl.isEmpty()) {
            skillParams.put("_attachment_url", ctx.attachmentUrl);
            log.info("Injected attachment URL into skill params: {}", ctx.attachmentUrl);
        }

        log.info("Coordinator routed to skill: {}, params: {}", skillName, skillParams);
        skillParams.put("_conversation_id", convId);

        // 候选点击协议文本主体提取（穿插/pendingSkill 重跑场景）：分支 5 重跑时 pendingParams 保留
        // 旧模糊词（如"小米"），技能层因 company_name 非空跳过 _user_input 兜底提取 → 点击候选后
        // 仍匹配同一批候选循环。此处按候选卡片固定句式（"帮我核实{企业名}的信息"）提取中间的新企业名
        // 覆盖旧值；含 18 位码的协议文本（RiskCheckCard）以码为主体，跳过名称提取
        String protocolName = intentPlannerService.extractCandidateClickName(
                String.valueOf(skillParams.getOrDefault("_user_input", "")));
        if (protocolName != null && !protocolName.isEmpty()) {
            skillParams.put("company_name", protocolName);
            log.info("Overrode company_name from candidate-click protocol text: '{}'", protocolName);
        }

        String assistantMsgId = UUID.randomUUID().toString();

        // skillRegistry.invoke 是同步阻塞，隔离到弹性线程池
        return Mono.fromCallable(() -> skillRegistry.invoke(skillName, userId, skillParams))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {
                    // 构建事件流
                    Flux<String> eventFlux;

                    if (result.containsKey("error")) {
                        String errorMsg = (String) result.get("error");
                        eventFlux = Flux.just(
                                sseEvent("text_delta", Map.of("content", errorMsg), assistantMsgId, null),
                                sseEvent("text_done", Map.of("content", errorMsg), assistantMsgId, null)
                        );
                        // 技能执行出错也视为本步骤真正结束：规划模式下标记 FAILED 并暂停征求用户确认是否继续（否则规划会卡住）
                        if (ctx.planActive) {
                            ContextMemoryService.PlanStep curStep = contextMemoryService.getCurrentPlanStep(convId);
                            if (curStep != null) {
                                curStep.status = ContextMemoryService.PlanStatus.FAILED;
                                curStep.summary = skillName + "（失败）";
                            }
                            log.info("Plan step {} failed with error, pausing for user confirmation", skillName);
                            eventFlux = eventFlux.concatWith(
                                    intentPlannerService.stepDoneAndConfirm(convId,
                                            planInvoker(convId, userId, conv)));
                        }
                    } else {
                        String action = (String) result.getOrDefault("action", "");
                        // 报告生成引导阶段：generate_report 返回模板列表/跳转编辑页（stage=templates/redirect）
                        // 仅把用户引导出对话流，报告实际在 H5 编辑页面异步生成——此阶段不算步骤结束，
                        // 规划模式下标记 WAITING_EXTERNAL，等前端轮询到报告生成完成后才标 DONE/FAILED
                        boolean isReportStage = "generate_report".equals(skillName)
                                && ("templates".equals(result.get("stage"))
                                    || "redirect".equals(result.get("stage")));
                        // 模糊匹配候选待确认：技能返回 options 候选列表（ambiguous/not_found 的相似企业
                        // 选项）时，当前规划步骤尚未真正结束——必须等用户点击某个候选（或重新提供准确
                        // 名称/信用代码）后才结束推进下一步；与 candidates 分支的"等待用户输入"语义一致
                        boolean hasFuzzyOptions = result.get("options") instanceof List<?> fuzzyOpts
                                && !fuzzyOpts.isEmpty();
                        // 步骤结束点：技能显式声明 _step_done（true=本次返回已到达步骤结束点，协调器据此
                        // 标记 DONE；false=步骤未结束——等待用户输入补充/选择，或如尽调报告引导阶段等待
                        // 外部异步完成）。技能未声明时按 action 兜底推导旧终结性集合
                        // （result/summary/detail、不带候选列表的 not_found/ambiguous），保证未改造的
                        // 技能/兜底路径行为与改造前一致
                        boolean stepDone = result.containsKey(Skill.KEY_STEP_DONE)
                                ? Boolean.TRUE.equals(result.get(Skill.KEY_STEP_DONE))
                                : ("result".equals(action) || "summary".equals(action) || "detail".equals(action)
                                    || ("not_found".equals(action) && !hasFuzzyOptions)
                                    || ("ambiguous".equals(action) && !hasFuzzyOptions));
                        if ("summary".equals(action)) {
                            eventFlux = Flux.just(sseEvent("potential_customer_summary", result, assistantMsgId, null));
                        } else if ("detail".equals(action)) {
                            eventFlux = Flux.just(sseEvent("potential_customer_detail", result, assistantMsgId, null));
                        } else if ("candidates".equals(action)) {
                            result.put("_skill_name", skillName);
                            eventFlux = Flux.just(sseEvent("company_name_candidates", result, assistantMsgId, null));
                            // 将技能解析出的 keyword 合并回 skillParams（让下一轮持有企业名上下文）
                            if (result.containsKey("keyword") && !skillParams.containsKey("company_name")) {
                                skillParams.put("company_name", result.get("keyword"));
                            }
                            if (ctx.planActive) {
                                // 规划模式：不设 pendingSkill，标记当前规划步骤等待用户输入
                                // 技能解析出的候选关键词写回当前步骤 params，作为后续无主体步骤的继承源
                                ContextMemoryService.PlanStep curStep = contextMemoryService.getCurrentPlanStep(convId);
                                if (curStep != null && skillParams.containsKey("company_name")) {
                                    curStep.params.put("company_name", skillParams.get("company_name"));
                                    // 技能解析出真实主体关键词 → 清除静态继承占位标记
                                    curStep.params.remove("_inherited_subject");
                                }
                                contextMemoryService.setPlanStepNeedsInput(convId, true);
                                log.info("Plan step waiting for input: {} (candidates)", skillName);
                                // 步骤进入等待用户输入态 → 发状态快照同步面板（WAITING_INPUT 徽章）
                                eventFlux = eventFlux.concatWith(
                                        Flux.just(intentPlannerService.planStatusEvent(convId)));
                            } else {
                                // 保存待处理技能上下文，下一条用户消息将直接回到此技能
                                contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                                log.info("Pending skill set: {} (candidates)", skillName);
                            }
                            // 候选选项卡消息由下方统一"存储助手消息"持久化（JSON 版，前端加载时
                            // 归一化 action=candidates → company_name_candidates），不在分支内重复插入
                            // ——否则与统一持久化双写同 id 消息，切换会话后候选列表重复显示
                        } else if ("info_needed".equals(action)) {
                            String prompt = (String) result.getOrDefault("message", "");
                            eventFlux = Flux.just(
                                    sseEvent("text_delta", Map.of("content", prompt), assistantMsgId, null),
                                    sseEvent("text_done", Map.of("content", prompt), assistantMsgId, null)
                            );
                            // 引导提问由下方统一"存储助手消息"持久化（JSON 版，前端加载时归一化为
                            // 纯文本，与实时 text_delta/text_done 路径一致）——不在分支内重复插入，
                            // 否则与统一持久化双写同 id 消息，切换会话后"请问您要查询哪家企业"重复
                            // 将技能已解析的参数字段合并回 skillParams（如 company_name, credit_code）
                            // 避免下一轮参数丢失导致技能重新从阶段一/二开始
                            if (result.containsKey("company_name")) {
                                skillParams.put("company_name", result.get("company_name"));
                            }
                            if (result.containsKey("credit_code")) {
                                skillParams.put("credit_code", result.get("credit_code"));
                            }
                            if (ctx.planActive) {
                                // 规划模式：技能识别出的关键参数先写回当前步骤 params（作为后续无主体步骤的
                                // 继承源）并广播到规划中其他缺失该键的步骤（一次问清），再标记当前步骤等待
                                // 用户输入补充剩余参数。写回/广播仅限非空值：技能层清洗功能词残渣后返回的
                                // 空主体（如 LLM 把"股东信息"填进 company_name，清洗后为空）若写回会污染
                                // 参数，空值广播会使其他无主体步骤 needsInput 被误置为 false。此时 result
                                // 含该键但值为空、而步骤 params 原参数非空（垃圾键）→ 移除垃圾键，否则
                                // mergeUserInput 因"已有主体"拒绝覆盖用户新输入 → 每次重跑仍询问 → 死循环。
                                ContextMemoryService.PlanStep curStep = contextMemoryService.getCurrentPlanStep(convId);
                                if (result.containsKey("company_name")) {
                                    String resolvedName = result.get("company_name") == null
                                            ? "" : String.valueOf(result.get("company_name")).trim();
                                    if (!resolvedName.isEmpty()) {
                                        if (curStep != null) {
                                            curStep.params.put("company_name", resolvedName);
                                            // 技能解析出真实主体 → 清除静态继承占位标记
                                            curStep.params.remove("_inherited_subject");
                                        }
                                        intentPlannerService.broadcastParams(convId, "company_name", resolvedName);
                                    } else if (curStep != null
                                            && skillParams.containsKey("company_name")
                                            && !String.valueOf(skillParams.get("company_name")).trim().isEmpty()) {
                                        // 技能清洗判定原参数为功能词垃圾值 → 从步骤参数移除，让用户新输入可覆盖
                                        curStep.params.remove("company_name");
                                        log.info("Plan step removed garbage company_name param (cleaned empty): {}",
                                                skillParams.get("company_name"));
                                    }
                                }
                                if (result.containsKey("credit_code")) {
                                    String resolvedCode = result.get("credit_code") == null
                                            ? "" : String.valueOf(result.get("credit_code")).trim();
                                    if (!resolvedCode.isEmpty()) {
                                        if (curStep != null) {
                                            curStep.params.put("credit_code", resolvedCode);
                                            curStep.params.remove("_inherited_subject");
                                        }
                                        intentPlannerService.broadcastParams(convId, "credit_code", resolvedCode);
                                    } else if (curStep != null
                                            && skillParams.containsKey("credit_code")
                                            && !String.valueOf(skillParams.get("credit_code")).trim().isEmpty()) {
                                        // 技能清洗判定原参数为功能词垃圾值 → 从步骤参数移除，让用户新输入可覆盖
                                        curStep.params.remove("credit_code");
                                        log.info("Plan step removed garbage credit_code param (cleaned empty): {}",
                                                skillParams.get("credit_code"));
                                    }
                                }
                                contextMemoryService.setPlanStepNeedsInput(convId, true);
                                log.info("Plan step waiting for input: {} (info_needed)", skillName);
                                // 步骤进入等待用户输入态 → 发状态快照同步面板（WAITING_INPUT 徽章）
                                eventFlux = eventFlux.concatWith(
                                        Flux.just(intentPlannerService.planStatusEvent(convId)));
                            } else {
                                // 保存待处理技能上下文，下一条用户消息将直接回到此技能
                                contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                                log.info("Pending skill set: {} (info_needed), params: {}", skillName, skillParams);
                            }
                        } else if ("result".equals(action) || "ambiguous".equals(action) || "not_found".equals(action)) {
                            String eventType = switch (skillName) {
                                case "query_due_diligence_reports" -> "historical_dd_query_result";
                                case "verify_business_license" -> "information_check_result";
                                case "query_company_basic_info", "query_shareholder_info", "query_beneficiary_info",
                                     "query_company_genealogy", "query_customs_auth", "query_customs_blacklist",
                                     "query_account_freeze_tag", "query_credit_granting",
                                     "query_pboc_account_control" -> "company_query_result";
                                default -> "risk_check_result";
                            };
                            // 将 skill_name 注入到结果中，方便前端根据技能类型路由卡片
                            result.put("_skill_name", skillName);
                            eventFlux = Flux.just(sseEvent(eventType, result, assistantMsgId, null));
                            // 规划模式：ambiguous/not_found 带候选列表 → 本步骤不结束，标记当前步骤等待用户
                            // 显式选择（与 candidates 分支一致）；技能解析出的 keyword 写回步骤 params，作为
                            // 后续无主体步骤的继承源；选择结果由下一轮用户输入合并后重跑当前步骤，命中后才
                            // 结束并推进下一步
                            if (ctx.planActive && hasFuzzyOptions) {
                                ContextMemoryService.PlanStep curStep = contextMemoryService.getCurrentPlanStep(convId);
                                if (curStep != null && result.containsKey("keyword")) {
                                    Object kw = result.get("keyword");
                                    if (kw != null && !String.valueOf(kw).trim().isEmpty()) {
                                        curStep.params.put("company_name", String.valueOf(kw).trim());
                                        // 技能解析出真实主体关键词 → 清除静态继承占位标记
                                        curStep.params.remove("_inherited_subject");
                                    }
                                }
                                contextMemoryService.setPlanStepNeedsInput(convId, true);
                                log.info("Plan step waiting for input: {} ({}+options)", skillName, action);
                                // 步骤进入等待用户输入态 → 发状态快照同步面板（WAITING_INPUT 徽章）
                                eventFlux = eventFlux.concatWith(
                                        Flux.just(intentPlannerService.planStatusEvent(convId)));
                            } else if (hasFuzzyOptions) {
                                // 非规划模式（意图穿插/单技能）：模糊匹配候选待用户点击选择——保存待处理技能
                                // 上下文，下一条用户消息直接回到此技能重跑（与 candidates/info_needed 分支一致）。
                                // 关键：pendingSkill 占用使 resumePlanIfSuspended 推迟"穿插任务已完成"确认卡片，
                                // 直到用户点击候选命中结果、clearPendingSkill 后才发送——穿插任务的模糊匹配
                                // 同样必须点击候选选项才算完成
                                if (result.containsKey("keyword")
                                        && !skillParams.containsKey("company_name")
                                        && result.get("keyword") != null
                                        && !String.valueOf(result.get("keyword")).trim().isEmpty()) {
                                    skillParams.put("company_name", String.valueOf(result.get("keyword")).trim());
                                }
                                contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                                log.info("Pending skill set: {} ({}+options), awaiting user selection",
                                        skillName, action);
                            }
                        } else {
                            eventFlux = Flux.empty();
                        }

                        // 更新上下文记忆（若返回了企业信息）
                        if ("result".equals(action) && result.get("credit_code") != null) {
                            contextMemoryService.update(convId,
                                    (String) result.getOrDefault("company_name", ""),
                                    (String) result.get("credit_code"));
                            log.info("Context updated: {} ({})", result.get("company_name"), result.get("credit_code"));
                        }

                        // 规划模式：记录本步骤结果摘要（供 plan_summary 汇总）并标记 DONE（推进由下方链式触发）
                        // 步骤结束点以技能声明的 _step_done 为准：info_needed/candidates/带候选列表的
                        // not_found、ambiguous（等待用户输入补充/选择）不算步骤结束，不在此标 DONE
                        if (ctx.planActive) {
                            ContextMemoryService.PlanStep curStep = contextMemoryService.getCurrentPlanStep(convId);
                            if (curStep != null) {
                                if (isReportStage) {
                                    // 报告生成仅停留在"选模板/跳转编辑页"引导阶段（H5 异步生成中）：
                                    // 不标 DONE、不结束，置 WAITING_EXTERNAL 由前端进度卡轮询完成后通知收尾
                                    curStep.status = ContextMemoryService.PlanStatus.WAITING_EXTERNAL;
                                    curStep.summary = "生成尽调报告（等待报告生成完成）";
                                    log.info("Plan step {} marked WAITING_EXTERNAL (stage={})",
                                            skillName, result.get("stage"));
                                    // 步骤等待外部异步完成 → 发状态快照同步面板（WAITING_EXTERNAL 徽章）
                                    eventFlux = eventFlux.concatWith(
                                            Flux.just(intentPlannerService.planStatusEvent(convId)));
                                } else if (stepDone) {
                                    // 技能结果解析出的主体写回步骤 params，作为后续无主体步骤的继承源
                                    // （如第一步只给了 company_name，执行中才解析出 credit_code）
                                    // 同时清除静态继承占位标记：写回的是执行期解析出的真实主体
                                    if (result.containsKey("credit_code")) {
                                        curStep.params.put("credit_code", result.get("credit_code"));
                                        curStep.params.remove("_inherited_subject");
                                    }
                                    if (result.containsKey("company_name")) {
                                        curStep.params.put("company_name", result.get("company_name"));
                                        curStep.params.remove("_inherited_subject");
                                    }
                                    if ("result".equals(action)) {
                                        Object company = result.getOrDefault("company_name", "");
                                        curStep.summary = skillName + "（" + (company == null ? "" : company) + "）";
                                    } else {
                                        curStep.summary = skillName + "（未找到）";
                                    }
                                    curStep.status = ContextMemoryService.PlanStatus.DONE;
                                }
                            }
                        }

                        // 清理待处理技能（技能已完成或未找到结果），并清除已使用的附件
                        // 模糊匹配待确认（带候选列表的 not_found）保留待处理上下文，等待用户选择后重试
                        if ("result".equals(action) || ("not_found".equals(action) && !hasFuzzyOptions)) {
                            contextMemoryService.clearPendingSkill(convId);
                            contextMemoryService.clearAttachment(convId);
                        }
                        // reset或result/not_found时重置重试计数（新技能调用从0开始）
                        // 模糊匹配待确认（带候选列表的 ambiguous/not_found）不重置，与 candidates 一致
                        if (!"candidates".equals(action) && !"info_needed".equals(action) && !hasFuzzyOptions) {
                            ctx.pendingSkillRetry = 0;
                        }

                        // 存储助手消息（同步，顺序执行）
                        try {
                            String summaryText = mapper.writeValueAsString(result);
                            Message asstMsg = new Message(assistantMsgId, "assistant", summaryText, Instant.now().toString());
                            // 按穿插边界规则定位插入（与前端 upsertCardMessage 占位消费后的
                            // insertBeforeBoundaryCard 一致）：穿插中结果卡保持在恢复确认卡上方，
                            // 避免切换会话后结果卡跑到穿插对话之后位置错乱
                            insertBeforeBoundaryCard(conv.getMessages(), asstMsg);
                            conv.setUpdatedAt(asstMsg.getCreatedAt());
                        } catch (Exception e) {
                            log.error("Failed to serialize result: {}", e.getMessage());
                        }

                        // 跟踪技能调用 + 后续建议（报告引导阶段不预测，避免模板卡后追加多余建议）
                        if ("result".equals(action) && !isReportStage) {
                            String credit = (String) result.getOrDefault("credit_code", "");
                            if (credit != null && !credit.isEmpty()) {
                                conversationService.recordSkillCall(convId, credit, skillName);
                            }

                            List<String> allSkills = conversationService.getAllSkills(convId);
                            List<String> companySkills = credit != null && !credit.isEmpty()
                                    ? conversationService.getCompanySkills(convId, credit) : List.of();

                            String followUpText = followUpService.predictFollowUp(
                                    skillName, action,
                                    (String) result.getOrDefault("company_name", ""),
                                    credit,
                                    allSkills, companySkills);

                            if (followUpText != null) {
                                // 追加 follow_up_suggestion 事件
                                eventFlux = eventFlux.concatWith(
                                        Flux.just(sseEvent("follow_up_suggestion",
                                                Map.of("content", followUpText), assistantMsgId, null))
                                );
                            }
                        }

                        // 规划模式：步骤结束点（技能声明 _step_done=true）→ 暂停征求用户确认是否继续下一步。
                        // 只有 info_needed/candidates/带候选列表的 ambiguous、not_found（等待用户输入补充/选择）不结束，
                        // 由用户补充或点击候选后重跑当前步骤；报告引导阶段（templates/redirect）也不结束，
                        // 等待 H5 报告生成完成后由 report-complete 通知收尾
                        if (stepDone && ctx.planActive && !isReportStage) {
                            log.info("Plan step {} completed ({}), pausing for user confirmation", skillName, action);
                            eventFlux = eventFlux.concatWith(
                                    intentPlannerService.stepDoneAndConfirm(convId,
                                            planInvoker(convId, userId, conv)));
                        }
                    }

                    // 在最前面发送 meta 事件
                    return Flux.just(sseEvent("meta", Map.of("conversation_id", convId), assistantMsgId, null))
                            .concatWith(eventFlux);
                })
                .doOnComplete(() -> {
                    // 更新标题（如有必要）
                    if ("新对话".equals(conv.getTitle()) && conv.getMessages().size() >= 2) {
                        for (Message m : conv.getMessages()) {
                            if ("user".equals(m.getRole())) {
                                String content = m.getContent();
                                conv.setTitle(content.length() > 30 ? content.substring(0, 30) + "..." : content);
                                break;
                            }
                        }
                    }
                });
    }

    /**
     * 规划模式技能执行器：把单意图 decision 交给对应的处理器。
     * chat 意图（非技能问答）→ 对话助手流式回答 params.question；其余 → 单技能执行。
     */
    private IntentPlannerService.SkillInvoker planInvoker(String convId, String userId, Conversation conv) {
        return decision -> {
            String skill = (String) decision.getOrDefault("skill", "");
            if ("chat".equals(skill)) {
                log.info("Plan chat step answering: {}", extractChatQuestion(decision));
                return handleChat(convId, conv, extractChatQuestion(decision));
            }
            return handleSingleSkill(decision, convId, userId, conv);
        };
    }

    /**
     * 从决策/意图中提取 chat 步骤的问题文本：优先 params.question，其次 _user_input（整条用户消息）。
     */
    @SuppressWarnings("unchecked")
    private String extractChatQuestion(Map<String, Object> decision) {
        Object rawParams = decision.getOrDefault("params", Map.of());
        if (!(rawParams instanceof Map<?, ?> params)) return "请继续";
        Object q = params.get("question");
        String question = q == null ? "" : String.valueOf(q);
        if (question.isBlank()) {
            Object u = params.get("_user_input");
            question = u == null ? "" : String.valueOf(u);
        }
        return question.isBlank() ? "请继续" : question.trim();
    }

    /**
     * 处理普通聊天分支（流式）---兜底
     */
    private Flux<String> handleChat(String convId, Conversation conv, String userMessage) {
        String assistantMsgId = UUID.randomUUID().toString();
        StringBuilder fullContent = new StringBuilder();

        // 先发送 meta（告知前端 conversation_id），然后 text_start，流式输出，最后 text_done
        // 传入历史消息（不含当前这条刚加入的用户消息）
        List<Message> history = conv.getMessages().size() > 1
                ? conv.getMessages().subList(0, conv.getMessages().size() - 1)
                : List.of();
        
        return Flux.just(sseEvent("meta", Map.of("conversation_id", convId), null, convId))
                .concatWith(Flux.just(sseEvent("text_start", null, assistantMsgId, null)))
                .concatWith(agentService.streamChat(userMessage, history)
                        .doOnNext(delta -> fullContent.append(delta))
                        .map(delta -> sseEvent("text_delta", Map.of("content", delta), assistantMsgId, null))
                )
                .concatWith(Flux.just(sseEvent("text_done",
                        Map.of("content", fullContent.toString()), assistantMsgId, null)))
                .doFinally(signal -> {
                    // 存储助手消息：正常完成存完整内容；被强制终止（doOnCancel）时存已生成的部分内容
                    // 这样强制停止后切换会话，中途已生成的内容仍保留在对话记录中
                    if (fullContent.length() > 0) {
                        Message asstMsg = new Message(assistantMsgId, "assistant",
                                fullContent.toString(), Instant.now().toString());
                        // 与前端占位消息位置一致：穿插中回复保持在恢复确认卡上方，
                        // 避免穿插对话落在恢复确认卡之后位置错乱
                        insertBeforeBoundaryCard(conv.getMessages(), asstMsg);
                        conv.setUpdatedAt(asstMsg.getCreatedAt());
                    }
                    // 更新标题
                    if ("新对话".equals(conv.getTitle()) && conv.getMessages().size() >= 2) {
                        for (Message m : conv.getMessages()) {
                            if ("user".equals(m.getRole())) {
                                String content = m.getContent();
                                conv.setTitle(content.length() > 30 ? content.substring(0, 30) + "..." : content);
                                break;
                            }
                        }
                    }
                });
    }

    // ---------- 任务规划卡片消息持久化（切换会话后卡片按原位置恢复，不消失） ----------

    /**
     * plan 状态快照 → plan_status SSE 事件 JSON（供 persistPlanCardEvent 复用持久化：
     * reportComplete 收尾的终态/确认态快照按 planId 原地更新消息流面板，避免切换会话/刷新后
     * 面板停留在过时的"执行中"快照；快照 steps 为空（规划收尾清除）时移除面板）。
     */
    private String planSnapshotToEventJson(Map<String, Object> plan) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "plan_status");
        if (plan != null) {
            event.putAll(plan);
        }
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("Failed to serialize plan snapshot for persistence: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 统一拦截任务规划涉及的 SSE 事件，将卡片消息写入会话消息流（extra 承载结构化数据），
     * 切换会话后前端按消息顺序恢复渲染：
     * - plan_status → 按 planId 固定 id（plan-status-${planId}）upsert 规划面板消息；
     *   快照 steps 为空（规划收尾清除/被丢弃）→ 移除面板消息（与前端实时 upsert 语义一致）；
     * - plan_step_confirm / resume_confirm / plan_progress / plan_preview / plan_summary
     *   → 追加独立消息，extra 结构与前端 useChat.ts 本地生成完全一致；
     * - 其他事件不处理：技能结果（content=JSON）由 handleSingleSkill 持久化、
     *   report_generate_result 由前端 injectConversationReports 按报告任务恢复，避免重复。
     */
    private void persistPlanCardEvent(Conversation conv, String eventJson) {
        if (eventJson == null || eventJson.isBlank()) return;
        try {
            Map<String, Object> event = mapper.readValue(eventJson.trim(), new TypeReference<Map<String, Object>>() {});
            String type = (String) event.get("type");
            if (type == null) return;
            switch (type) {
                case "plan_status" -> upsertPlanStatusMessage(conv, event);
                case "plan_step_confirm", "resume_confirm", "plan_progress", "plan_preview", "plan_summary" ->
                        appendPlanCardMessage(conv, event, type);
                // 追问建议气泡（穿插意图的结果追问）：不持久化则切换会话后追问卡消失，
                // 与 plan_* 卡片一样写入消息流，切换后按穿插边界原位置恢复
                case "follow_up_suggestion" -> appendFollowUpSuggestion(conv, event);
                // 穿插恢复时重发的报告生成步骤卡片（模板选择/已选模板跳转/进度卡）：
                // 不持久化则切换会话后恢复重发的模板记录消失
                case "report_generate_result" -> persistReportGenerateResult(conv, event);
                default -> { /* 其他事件已有各自的持久化/恢复机制 */ }
            }
        } catch (Exception e) {
            log.warn("Failed to persist plan card event: {}", e.getMessage());
        }
    }

    /** plan_status 快照按 planId 固定 id 原地 upsert 面板消息（steps 为空或收尾终态 → 移除面板） */
    private void upsertPlanStatusMessage(Conversation conv, Map<String, Object> event) {
        List<?> steps = event.get("steps") instanceof List<?> s ? s : List.of();
        boolean hasSteps = !steps.isEmpty();
        // 收尾终态快照（全部步骤 DONE，或携带收尾汇总文案如"任务已结束/任务完成"，规划已结束）：
        // 不持久化面板——实时"任务完成"展示由前端 allDone 分支本地渲染承担，切换/刷新会话后的
        // 恢复由 plan_progress 气泡（"任务完成：..."）承担；若保留终态快照，切换会话时
        // getConversation 会加载并渲染规划卡，表现为"每个会话都残留任务规划卡"。
        // 注意：planStatusEvent(convId, summary) 只在收尾路径调用（stepDoneAndConfirm/
        // confirmContinue/endPlan/resumePlanIfSuspended 防御收尾），summary 非空即收尾，安全。
        boolean isClosing = hasSteps && (steps.stream().allMatch(s ->
                s instanceof Map<?, ?> m && "DONE".equals(String.valueOf(m.get("status"))))
                || (event.get("summary") != null && !String.valueOf(event.get("summary")).isBlank()));
        String planId = event.get("planId") == null ? "" : String.valueOf(event.get("planId"));
        if (planId.isBlank()) return; // 无 planId 的空快照（防御性事件），无需持久化
        String panelId = "plan-status-" + planId;
        // 快照字段拍平在事件顶层，extra 结构对齐前端 useChat.ts 的 {action:'plan_status', ...planData}
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("action", "plan_status");
        for (String key : List.of("active", "steps", "index", "confirming", "suspended", "planId", "summary")) {
            if (event.containsKey(key)) extra.put(key, event.get(key));
        }
        List<Message> messages = conv.getMessages();
        int existingIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (panelId.equals(messages.get(i).getId())) {
                existingIdx = i;
                break;
            }
        }
        if (existingIdx >= 0) {
            if (!hasSteps || isClosing) {
                // 面板已存在且快照无步骤（规划收尾清除/被丢弃）或为收尾终态 → 移除面板
                messages.remove(existingIdx);
            } else {
                Message panel = messages.get(existingIdx);
                panel.setExtra(extra);
                panel.setContent("");
            }
        } else if (hasSteps && !isClosing) {
            // 面板不存在且有步骤 → 按穿插边界规则插入（穿插中新建规划的面板保持在恢复确认卡上方，
            // 与前端 plan_status 占位消费后的 insertBeforeBoundaryCard 一致）
            Message panel = new Message(panelId, "assistant", "", Instant.now().toString());
            panel.setExtra(extra);
            insertBeforeBoundaryCard(messages, panel);
        }
        conv.setUpdatedAt(Instant.now().toString());
    }

    /** 确认卡/进度气泡事件追加为独立消息（extra 对齐前端 useChat.ts 生成结构） */
    private void appendPlanCardMessage(Conversation conv, Map<String, Object> event, String type) {
        String content = event.get("content") == null ? "" : String.valueOf(event.get("content"));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("action", type);
        extra.put("text", content);
        if ("plan_step_confirm".equals(type)) {
            if (event.containsKey("current_step")) extra.put("current_step", event.get("current_step"));
            if (event.containsKey("total_steps")) extra.put("total_steps", event.get("total_steps"));
            if (event.containsKey("next_step")) extra.put("next_step", event.get("next_step"));
        } else if ("resume_confirm".equals(type)) {
            if (event.containsKey("step_index")) extra.put("step_index", event.get("step_index"));
            if (event.containsKey("total_steps")) extra.put("total_steps", event.get("total_steps"));
            if (event.containsKey("step_desc")) extra.put("step_desc", event.get("step_desc"));
        }
        Message card = new Message(UUID.randomUUID().toString(), "assistant", "", Instant.now().toString());
        card.setExtra(extra);
        if ("resume_confirm".equals(type)) {
            // 恢复确认卡始终保持在对话最底部：新卡到达时移除旧未消费卡（连续穿插每张新卡替换旧卡），
            // 与前端 resume_confirm 插入逻辑一致，避免嵌套穿插时旧恢复卡残留导致位置错乱
            int oldIdx = lastUnconsumedCardIndex(conv.getMessages(), "resume_confirm");
            if (oldIdx >= 0) conv.getMessages().remove(oldIdx);
            conv.getMessages().add(card);
        } else {
            // 确认卡/进度气泡：插入到穿插边界之前（未消费恢复卡/确认卡之前），保持确认卡为步骤分界点，
            // 与前端 insertBeforeBoundaryCard 一致——否则切换会话后穿插对话会落在确认卡之后位置错乱
            insertBeforeBoundaryCard(conv.getMessages(), card);
        }
        conv.setUpdatedAt(card.getCreatedAt());
    }

    /** 追问建议气泡（follow_up 卡）：extra 对齐前端 useChat.ts 的 {action:'follow_up', text}，
     *  按穿插边界规则插入——穿插中保持在恢复确认卡上方，切换会话后追问气泡不消失 */
    private void appendFollowUpSuggestion(Conversation conv, Map<String, Object> event) {
        String content = event.get("content") == null ? "" : String.valueOf(event.get("content"));
        if (content.isBlank()) return;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("action", "follow_up");
        extra.put("text", content);
        Message card = new Message(UUID.randomUUID().toString(), "assistant", "", Instant.now().toString());
        card.setExtra(extra);
        insertBeforeBoundaryCard(conv.getMessages(), card);
        conv.setUpdatedAt(card.getCreatedAt());
    }

    /**
     * 报告生成步骤卡片持久化（type=report_generate_result，穿插恢复时由 IntentPlannerService
     * 重发模板选择/跳转/进度卡）：不持久化则穿插恢复重发的模板记录在切换会话后消失。按 stage 分发：
     * - templates → 模板选择卡（extra 对齐前端 ReportGenerateCard 渲染结构）；
     * - redirect → 已选模板跳转卡（结构同前端本地生成后经 /api/chat/card 持久化的卡片）；
     * - progress → 固定 id（report-progress-${reportId}）upsert，与前端 injectProgressMessage 的
     *   进度卡 id 一致，切换会话后 injectConversationReports 幂等替换不重复。
     */
    private void persistReportGenerateResult(Conversation conv, Map<String, Object> event) {
        String stage = event.get("stage") == null ? "" : String.valueOf(event.get("stage"));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("action", "result");
        extra.put("_skill_name", "generate_report");
        extra.put("stage", stage);
        if ("progress".equals(stage)) {
            String reportId = event.get("report_id") == null ? "" : String.valueOf(event.get("report_id"));
            if (reportId.isEmpty()) return;
            extra.put("report_id", reportId);
            String cardId = "report-progress-" + reportId;
            // 固定 id 幂等：已存在（前端切换会话时注入或本流程已持久化）则不重复插入
            for (Message m : conv.getMessages()) {
                if (cardId.equals(m.getId())) return;
            }
            Message card = new Message(cardId, "assistant", "", Instant.now().toString());
            card.setExtra(extra);
            insertBeforeBoundaryCard(conv.getMessages(), card);
            conv.setUpdatedAt(card.getCreatedAt());
            return;
        }
        // templates / redirect：透传模板相关字段（模板列表/机构/说明/已选模板信息）
        for (String key : List.of("templates", "organization", "message",
                "template_id", "template_name", "template_icon")) {
            if (event.containsKey(key)) extra.put(key, event.get(key));
        }
        Message card = new Message(UUID.randomUUID().toString(), "assistant", "", Instant.now().toString());
        card.setExtra(extra);
        insertBeforeBoundaryCard(conv.getMessages(), card);
        conv.setUpdatedAt(card.getCreatedAt());
    }

    // ---------- 规划卡片消息插入规则（与前端 useChat.ts 实时插入语义对齐） ----------

    /**
     * 判断是否为确认卡片按钮动作（plan_continue/plan_stop/plan_resume_yes/no JSON），
     * 与前端 useChat.ts isConfirmAction 同源（确认动作属于确认卡片本身，追加在卡片之后消费该卡片）。
     */
    private boolean isConfirmAction(String content) {
        String t = content == null ? "" : content.trim();
        if (!t.startsWith("{") || !t.endsWith("}")) return false;
        try {
            Map<String, Object> obj = mapper.readValue(t, new TypeReference<Map<String, Object>>() {});
            Object action = obj.get("action");
            return "plan_continue".equals(action) || "plan_stop".equals(action)
                    || "plan_resume_yes".equals(action) || "plan_resume_no".equals(action);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否为对穿插恢复确认卡片（resume_confirm）的文本回复
     * （与前端 useChat.ts isResumeReplyText 关键词判定同源）。
     */
    private boolean isResumeReplyText(String content) {
        String t = content == null ? "" : content.trim();
        if (t.startsWith("{") && t.endsWith("}")) return false;
        return t.contains("不用") || t.contains("不需要") || t.contains("算了")
                || t.contains("不了") || t.contains("不必") || t.contains("不要")
                || t.contains("回到") || t.contains("回去") || t.contains("恢复")
                || t.contains("继续之前的") || t.contains("接着之前")
                || t.equals("要") || t.equals("是");
    }

    /**
     * 判断确认/恢复卡片是否已被用户消费：其后存在确认动作（plan_continue/plan_stop/
     * plan_resume_yes/no）或穿插恢复文本回复 → 卡片仅是历史记录（用户已回应），不再是
     * 穿插/暂停边界；未消费的卡片才是边界（新步骤的卡片应显示在它之前）。
     */
    private boolean isCardConsumed(List<Message> messages, int idx) {
        for (int i = idx + 1; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (!"user".equals(m.getRole())) continue;
            String t = m.getContent() == null ? "" : m.getContent().trim();
            if (isConfirmAction(t) || isResumeReplyText(t)) return true;
        }
        return false;
    }

    /** 查找最后一张未消费的确认/恢复卡片索引（extra.action 匹配且未被用户回应），无则 -1 */
    private int lastUnconsumedCardIndex(List<Message> messages, String action) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            Map<String, Object> extra = m.getExtra();
            if (extra != null && action.equals(extra.get("action")) && !isCardConsumed(messages, i)) return i;
        }
        return -1;
    }

    /**
     * 插入到穿插边界之前（与前端 insertBeforeBoundaryCard 一致）：
     * 存在未消费 resume_confirm → 插到它之前（穿插区域对话保持在卡片上方）；
     * 否则未消费 plan_step_confirm → 插到它之前（确认卡为步骤分界点）；都无 → 追加末尾。
     */
    private void insertBeforeBoundaryCard(List<Message> messages, Message msg) {
        int resumeIdx = lastUnconsumedCardIndex(messages, "resume_confirm");
        if (resumeIdx >= 0) {
            messages.add(resumeIdx, msg);
            return;
        }
        int confirmIdx = lastUnconsumedCardIndex(messages, "plan_step_confirm");
        if (confirmIdx >= 0) {
            messages.add(confirmIdx, msg);
            return;
        }
        messages.add(msg);
    }

    /**
     * 用户/占位消息插入策略（与前端 insertInterleavingAware 一致）：
     * 穿插进行中（未消费 resume_confirm）→ 插到恢复卡之前（穿插对话保持在卡片上方）；
     * 否则移除失效的未消费"下一步"确认卡后追加末尾（穿插挂起时旧确认卡实时流中会被移除）。
     */
    private void insertInterleavingAware(List<Message> messages, Message msg) {
        int resumeIdx = lastUnconsumedCardIndex(messages, "resume_confirm");
        if (resumeIdx >= 0) {
            messages.add(resumeIdx, msg);
            return;
        }
        int confirmIdx = lastUnconsumedCardIndex(messages, "plan_step_confirm");
        if (confirmIdx >= 0) {
            messages.remove(confirmIdx);
        }
        messages.add(msg);
    }

    // ---------- SSE 辅助方法 ----------
    private String sseEvent(String type, Map<String, Object> data, String messageId, String conversationId) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            if (messageId != null) event.put("message_id", messageId);
            if (conversationId != null) event.put("conversation_id", conversationId);
            if (data != null) event.putAll(data);
            return  mapper.writeValueAsString(event) + "\n\n";
        } catch (Exception e) {
            return " {\"type\":\"error\",\"content\":\"serialization error\"}\n\n";
        }
    }
}