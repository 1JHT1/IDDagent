package com.IDDagent.controller;

import com.IDDagent.model.*;
import com.IDDagent.service.*;
import com.IDDagent.skill.SkillRegistry;
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

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String INTENT_SELECT_PREFIX = "【意图选择】";
    // 模板选择消息协议前缀：前端模板卡片点击后发送「【模板选择】<template_id>」文本消息，
    // 后端在 generate_report 重入时解析注入 template_id（见 handleSkill）
    private static final String TEMPLATE_SELECT_PREFIX = "【模板选择】";

    private final ConversationService conversationService;
    private final ContextMemoryService contextMemoryService;
    private final CoordinatorService coordinatorService;
    private final FollowUpService followUpService;
    private final AgentService agentService;
    private final SkillRegistry skillRegistry;
    private final TaskPlanner taskPlanner;

    public ChatController(ConversationService conversationService,
                          ContextMemoryService contextMemoryService,
                          CoordinatorService coordinatorService,
                          FollowUpService followUpService,
                          AgentService agentService,
                          SkillRegistry skillRegistry,
                          TaskPlanner taskPlanner) {
        this.conversationService = conversationService;
        this.contextMemoryService = contextMemoryService;
        this.coordinatorService = coordinatorService;
        this.followUpService = followUpService;
        this.agentService = agentService;
        this.skillRegistry = skillRegistry;
        this.taskPlanner = taskPlanner;
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
     * 报告生成完成通知：前端轮询到报告状态 completed 后调用，推进被挂起的多意图管道。
     * 幂等：无等待报告任务（waitingReportTask）时直接返回，不重复推进。
     * - 最后任务完成：持久化最终完成卡（kind=complete）+ 清理计划快照，返回 allDone=true
     * - 中间任务完成：把 pendingPipeline 首项升级为 pendingSkill（用户下一条消息 resume 续跑），
     *   返回 allDone=false 与剩余任务数
     */
    @PostMapping("/chat/report-completed")
    public Mono<Map<String, Object>> reportCompleted(@RequestBody Map<String, String> body,
                                                     @RequestAttribute("currentUser") UserInfo currentUser) {
        String conversationId = body.get("conversationId");
        Map<String, Object> resp = new LinkedHashMap<>();
        if (conversationId == null || conversationId.isBlank()) {
            resp.put("ok", false);
            resp.put("message", "conversationId 不能为空");
            return Mono.just(resp);
        }
        Map<String, Conversation> userConvs = conversationService.getUserConvs(currentUser.getId());
        if (!userConvs.containsKey(conversationId)) {
            resp.put("ok", false);
            resp.put("message", "会话不存在");
            return Mono.just(resp);
        }
        Conversation conv = userConvs.get(conversationId);
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
        if (ctx.waitingReportTask == null) {
            // 幂等：无挂起的报告任务（已推进过或非管道任务），直接返回
            resp.put("ok", true);
            resp.put("skipped", true);
            return Mono.just(resp);
        }
        int reportOrder = (int) ctx.waitingReportTask.getOrDefault("order", 0);
        // 完整计划快照（含已完成任务）：优先内存快照，丢失时从对话历史恢复
        List<Map<String, Object>> fullPlan = (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty())
                ? new ArrayList<>(ctx.pipelinePlan) : findPipelinePlanFromHistory(conv);
        int total = fullPlan != null ? fullPlan.size() : reportOrder;
        // 更新已持久化的初始执行计划卡（首卡）进度
        updatePipelinePlanCardOrder(conv, reportOrder);
        ctx.waitingReportTask = null;
        if (reportOrder >= total) {
            // 报告任务为管道最后一个任务：全部完成 → 持久化最终完成卡 + 清理计划快照
            if (fullPlan != null && !fullPlan.isEmpty()) {
                Map<String, Object> completeCard = new LinkedHashMap<>();
                completeCard.put("action", "pipeline");
                completeCard.put("kind", "complete");
                completeCard.put("plan", fullPlan);
                completeCard.put("total", fullPlan.size());
                completeCard.put("currentOrder", fullPlan.size());
                completeCard.put("paused", false);
                completeCard.put("completed", true);
                persistPipelineCard(conv, completeCard);
                // 全部完成：同步标记 plan/switch 卡完成态（与 executePipeline 完成分支一致）
                markPipelineCardsCompleted(conv);
            }
            if (ctx.pipelinePlan != null) ctx.pipelinePlan.clear();
            log.info("Pipeline completed after report generation: conv={}, order={}/{}",
                    conversationId, reportOrder, total);
            resp.put("ok", true);
            resp.put("completed", true);
            resp.put("allDone", true);
        } else {
            // 报告任务为中间任务：把 pendingPipeline 首项升级为 pendingSkill，
            // 用户下一条消息将 resume 续跑剩余任务
            if (ctx.pendingPipeline != null && !ctx.pendingPipeline.isEmpty()) {
                Map<String, Object> next = ctx.pendingPipeline.remove(0);
                Map<String, Object> params = next.get("params") instanceof Map
                        ? (Map<String, Object>) next.get("params") : new LinkedHashMap<>();
                contextMemoryService.setPendingSkill(conversationId, (String) next.get("skill"), params);
                log.info("Pipeline advanced after report generation: conv={}, task {}/{} done, next pendingSkill={}",
                        conversationId, reportOrder, total, next.get("skill"));
            }
            resp.put("ok", true);
            resp.put("completed", true);
            resp.put("allDone", false);
            resp.put("remaining", ctx.pendingPipeline != null ? ctx.pendingPipeline.size() : 0);
        }
        return Mono.just(resp);
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
        conv.getMessages().add(userMsg);
        conv.setUpdatedAt(now);
        // 消息追加后立即落盘：对话记录实时写入 data/conversations.json，
        // 否则消息只存内存、后端重启后全部丢失（persist 原本仅在创建/删除会话时触发）
        conversationService.persist();

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

        // 检查是否有待处理技能（上次技能正在等待用户补充信息）
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        boolean hasPendingSkill = ctx.hasPendingSkill();
        boolean hasPendingPipeline = ctx.hasPendingPipeline();

        // 初始事件
        String thinkingText = (hasPendingSkill || hasPendingPipeline) ? "正在查询，请稍候..." : "正在分析您的问题...";
        Flux<String> initEvent = Flux.just(sseEvent("thinking",
                Map.of("content", thinkingText), null, convId));

        // 主流程：isWaitingReport（报告生成中）→ pendingPipeline → pendingSkill → 前缀 → routeIntent
        Flux<String> mainFlow;
        if (ctx.isWaitingReport()) {
            // 异步报告仍在生成中（generate_report 跳转 H5 后挂起管道）：忽略本次消息，
            // 提示用户等待报告生成完成后自动继续（report-completed 接口推进管道）
            log.info("Conversation waiting for report completion, ignoring message: {}", finalMessage);
            mainFlow = Flux.just(sseEvent("pipeline_paused",
                    Map.of("hint", "报告正在生成中，请等待生成完成后自动继续"), null, convId));
        } else if (hasPendingPipeline) {
            // 恢复多意图管道：继续当前暂停任务 → 完成后执行剩余任务
            log.info("Resuming pending pipeline for conv: {}, remaining tasks: {}", convId, ctx.pendingPipeline.size());
            mainFlow = handleMultiResume(convId, userId, finalConv, finalMessage);
        } else if (hasPendingSkill) {
            // 检查重试上限：防止 pending skill 死循环
            if (ctx.pendingSkillRetry >= 3) {
                log.warn("Pending skill {} exceeded retry limit, clearing and falling back to Coordinator",
                        ctx.pendingSkillName);
                contextMemoryService.clearPendingSkill(convId);
                mainFlow = coordinatorService.routeIntent(finalMessage, finalConv.getMessages(), convId)
                        .flatMapMany(decision -> dispatchDecision(decision, convId, userId, finalConv, finalMessage));
            } else {
                ctx.pendingSkillRetry++;
                log.info("Pending skill {} retry {}/3: {}", ctx.pendingSkillName, ctx.pendingSkillRetry, finalMessage);
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
                mainFlow = handleSkill(decision, convId, userId, finalConv, -1);
            }
        } else if (body.getMessage() != null && body.getMessage().startsWith(INTENT_SELECT_PREFIX)) {
            // 意图选择前缀：跳过 LLM，直接路由
            String skillName = body.getMessage().substring(INTENT_SELECT_PREFIX.length()).trim();
            if (skillRegistry.get(skillName) != null) {
                log.info("Intent select prefix detected, routing directly to skill: {}", skillName);
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("action", "skill");
                decision.put("skill", skillName);
                decision.put("params", new LinkedHashMap<>());
                decision.put("reason", "用户意图选择");
                mainFlow = handleSkill(decision, convId, userId, finalConv, -1);
            } else {
                log.warn("Intent select prefix with unknown skill: {}, falling back to Coordinator", skillName);
                mainFlow = coordinatorService.routeIntent(finalMessage, finalConv.getMessages(), convId)
                        .flatMapMany(decision -> dispatchDecision(decision, convId, userId, finalConv, finalMessage));
            }
        } else if (body.getMessage() == null || body.getMessage().trim().isEmpty()) {
            log.info("Empty message with attachments, routing directly to chat for purpose inquiry");
            mainFlow = handleChat(convId, finalConv, finalMessage);
        } else {
            // 无待处理技能 → 走 Coordinator 三层路由
            mainFlow = coordinatorService.routeIntent(finalMessage, finalConv.getMessages(), convId)
                    .flatMapMany(decision -> dispatchDecision(decision, convId, userId, finalConv, finalMessage));
        }

        return initEvent.concatWith(mainFlow)
                // 强制终止检查：一旦该会话被标记为取消，立即截断剩余事件流
                .takeWhile(e -> !contextMemoryService.isCancelled(convId))
                // 所有事件流结束后发送 done 事件
                .concatWith(Flux.just(sseEvent("done", Map.of("conversation_id", convId), null, convId)))
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
     * 统一分发决策：skill / chat / clarify / multi
     */
    private Flux<String> dispatchDecision(Map<String, Object> decision, String convId,
                                          String userId, Conversation conv, String userMessage) {
        String action = (String) decision.getOrDefault("action", "chat");
        return switch (action) {
            case "skill" -> handleSkill(decision, convId, userId, conv, -1);
            case "clarify" -> handleClarify(decision, convId, conv);
            case "multi" -> handleMulti(decision, convId, userId, conv);
            default -> handleChat(convId, conv, userMessage);
        };
    }

    /**
     * 处理技能分支（非阻塞）
     * @param multiIndex 多意图管道中的任务序号（-1 表示单技能）
     */
    private Flux<String> handleSkill(Map<String, Object> decision, String convId,
                                     String userId, Conversation conv, int multiIndex) {
        String skillName = (String) decision.getOrDefault("skill", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> skillParams = new LinkedHashMap<>(
                (Map<String, Object>) decision.getOrDefault("params", Map.of()));

        // 模板选择消息协议：【模板选择】<template_id>（前端模板卡片点击后发送）。
        // generate_report 展示模板列表时已设置 pendingSkill，用户点击模板后此消息
        // 重入本技能；在此解析并注入 template_id，使技能跳过模板列表直接返回跳转信息。
        // 解析放在 handleSkill 而非各分支：主流程 pendingSkill 分支与 handleMultiResume
        // 都经此方法重入技能，单点覆盖所有路径（含单技能与多意图管道场景）
        String pendingInput = (String) skillParams.get("_user_input");
        if ("generate_report".equals(skillName) && pendingInput != null
                && pendingInput.startsWith(TEMPLATE_SELECT_PREFIX)) {
            String tid = pendingInput.substring(TEMPLATE_SELECT_PREFIX.length()).trim();
            if (!tid.isEmpty()) {
                skillParams.put("template_id", tid);
                log.info("Template selection resolved for generate_report: {}", tid);
            }
        }

        // 上下文记忆补全
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);

        // LLM 提取结果守卫（必须在 ctx 检查之外）：company_name 与 credit_code 独立校验、互不连坐——
        // - company_name 若是上下文指代词（"这家公司"，LLM 误提取）、泛企业指称（"企业/个企业"）、
        //   疑问句残留或明显非企业名，仅移除 company_name，交由上下文补全/缺参询问
        // - credit_code 显式携带但非 18 位合法格式（如 LLM 误把公司名塞入）时仅移除 credit_code
        //   避免技能拿脏值做模糊匹配永远 not_found，堵死缺企业名的询问提示
        String paramCompany = (String) skillParams.get("company_name");
        if (paramCompany != null && !CompanyNameExtractor.isValidCompanyName(paramCompany)) {
            skillParams.remove("company_name");
            log.info("Removed invalid company_name '{}', will ask user for a real company name", paramCompany);
        }
        String paramCode = (String) skillParams.get("credit_code");
        if (paramCode != null && !CompanyNameExtractor.isValidCreditCode(paramCode)) {
            skillParams.remove("credit_code");
            log.info("Removed invalid credit_code '{}'", paramCode);
        }

        // 上下文记忆补全（宽松策略）：credit_code / company_name 各自独立补全，
        // 只要技能参数中缺失且上下文有值即补——此前要求两个参数同时缺失才补全，
        // 导致多意图管道后续任务（如企业风险预查）LLM 只提取了 company_name
        // 而缺 credit_code 时跳过补全，技能拿不到精确信用代码再次模糊匹配弹企业选择卡。
        // 例外：本次消息已明确给出新的企业名（与上下文企业不同）时视为切换查询对象，
        // 不补全旧企业的 credit_code——技能内部 credit_code 优先于 company_name，
        // 强行补全会查到旧企业而忽略用户新指定的企业（如先查小米，再问"云禾科技的法人信息"
        // 却返回小米数据）。用 skillParams 当前值判断（无效名已被守卫移除后视为指代上下文企业）
        if (!ctx.isEmpty()) {
            String effectiveCompany = (String) skillParams.get("company_name");
            boolean sameCompany = effectiveCompany == null || effectiveCompany.isEmpty()
                    || ctx.companyName == null || ctx.companyName.isEmpty()
                    || ctx.companyName.contains(effectiveCompany)
                    || effectiveCompany.contains(ctx.companyName);
            if (!skillParams.containsKey("credit_code")
                    && ctx.creditCode != null && !ctx.creditCode.isEmpty()
                    && sameCompany) {
                skillParams.put("credit_code", ctx.creditCode);
                log.info("Auto-filled credit_code: {}", ctx.creditCode);
            }
            if (!skillParams.containsKey("company_name")
                    && ctx.companyName != null && !ctx.companyName.isEmpty()) {
                skillParams.put("company_name", ctx.companyName);
                log.info("Auto-filled company_name: {}", ctx.companyName);
            }
        }

        // 注入最新上传的附件 URL（如有），供技能解析营业执照等附件
        if (ctx.attachmentUrl != null && !ctx.attachmentUrl.isEmpty()) {
            skillParams.put("_attachment_url", ctx.attachmentUrl);
            log.info("Injected attachment URL into skill params: {}", ctx.attachmentUrl);
        }

        log.info("Coordinator routed to skill: {}, params: {}", skillName, skillParams);
        skillParams.put("_conversation_id", convId);

        String assistantMsgId = UUID.randomUUID().toString();

        // "以上都不是"：用户在模糊匹配候选列表中点击"以上都不是"，表明所有候选均非目标企业。
        // 候选卡片弹出时已设置 pendingSkill，此消息经主流程 pendingSkill 分支 / handleMultiResume
        // 重入本技能；在技能调用前拦截，返回友好提示并保留 pendingSkill 等待用户重新提供准确企业
        // 信息——否则 CompanyNameExtractor 会把该文本当企业名再次模糊匹配，重弹候选卡片甚至死循环
        if (pendingInput != null && pendingInput.contains("以上都不是")) {
            // 清空可能残留的企业参数，避免下一轮上下文补全复用旧企业名再次触发模糊匹配
            skillParams.remove("company_name");
            skillParams.remove("credit_code");
            String rejectPrompt = "以上候选企业均不是您要找的目标，请提供准确的企业名称或统一信用代码，我将为您重新查询。";
            contextMemoryService.setPendingInputHint(convId, rejectPrompt);
            contextMemoryService.setPendingSkill(convId, skillName, skillParams);
            log.info("User rejected all candidates (以上都不是), pending skill {} kept for re-query", skillName);
            return Flux.just(
                    sseEvent("text_delta", Map.of("content", rejectPrompt), assistantMsgId, null),
                    sseEvent("text_done", Map.of("content", rejectPrompt), assistantMsgId, null)
            );
        }

        // skillRegistry.invoke 是同步阻塞，隔离到弹性线程池
        return Mono.fromCallable(() -> skillRegistry.invoke(skillName, userId, skillParams))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {
                    // 构建事件流
                    Flux<String> eventFlux;
                    // 模板选择阶段标志：generate_report 展示模板列表（stage=templates）时
                    // 技能尚未完成任务，不发送 task_done、不清理 pendingSkill、不触发后续
                    // 建议，而是暂停管道等待用户点击模板后重入（与 candidates/info_needed 一致）
                    boolean templateStage = "generate_report".equals(skillName)
                            && "templates".equals(result.get("stage"));
                    // 跳转阶段（stage=redirect）：generate_report 已返回跳转 H5 编辑页信息，
                    // 报告由用户在 H5 页面异步生成，本任务尚未真正完成：不发 task_done、
                    // 挂起管道（waitingReportTask）等待报告完成后由 report-completed 接口推进
                    boolean redirectStage = "generate_report".equals(skillName)
                            && "redirect".equals(result.get("stage"));

                    if (result.containsKey("error")) {
                        String errorMsg = (String) result.get("error");
                        eventFlux = Flux.just(
                                sseEvent("text_delta", Map.of("content", errorMsg), assistantMsgId, null),
                                sseEvent("text_done", Map.of("content", errorMsg), assistantMsgId, null)
                        );
                    } else {
                        String action = (String) result.getOrDefault("action", "");
                        if ("summary".equals(action)) {
                            eventFlux = Flux.just(sseEvent("potential_customer_summary", result, assistantMsgId, null));
                        } else if ("detail".equals(action)) {
                            eventFlux = Flux.just(sseEvent("potential_customer_detail", result, assistantMsgId, null));
                        } else if ("candidates".equals(action) || "ambiguous".equals(action)) {
                            // ambiguous（企业名多候选）与 candidates 同处理：发企业选择卡片 + 保存待处理技能，
                            // 否则用户点击卡片选项后无 pendingSkill 会重新走 Coordinator 提取 → 再次多候选 → 死循环弹卡片
                            result.put("_skill_name", skillName);
                            eventFlux = Flux.just(sseEvent("company_name_candidates", result, assistantMsgId, null));
                            // 将技能解析出的 keyword 合并回 skillParams（让下一轮持有企业名上下文）
                            if (result.containsKey("keyword") && !skillParams.containsKey("company_name")) {
                                skillParams.put("company_name", result.get("keyword"));
                            }
                            // 保存待处理技能上下文，下一条用户消息将直接回到此技能
                            contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                            log.info("Pending skill set: {} ({})", skillName, action);
                            // 多意图管道内任务等待企业选择同样属于"暂停"：追加 pipeline_paused 事件，
                            // 前端据此把任务清单卡标记 paused，避免本条 SSE 流结束时 done 事件
                            // 误判"currentOrder >= total"而弹出"N 项任务已完成"完成卡
                            if (multiIndex >= 0
                                    || (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty())) {
                                Map<String, Object> pausedData = new LinkedHashMap<>();
                                pausedData.put("hint", result.getOrDefault("message", "请选择企业"));
                                eventFlux = eventFlux.concatWith(
                                        Flux.just(sseEvent("pipeline_paused", pausedData, null, convId)));
                            }
                        } else if ("info_needed".equals(action)) {
                            String prompt = (String) result.getOrDefault("message", "");
                            eventFlux = Flux.just(
                                    sseEvent("text_delta", Map.of("content", prompt), assistantMsgId, null),
                                    sseEvent("text_done", Map.of("content", prompt), assistantMsgId, null)
                            );
                            // 记录暂停提示（如"请上传营业执照图片"），多意图管道暂停时透传给前端任务清单卡片，
                            // 明确提醒用户需要上传附件还是补充文本信息
                            contextMemoryService.setPendingInputHint(convId, prompt);
                            // 将技能已解析的参数字段合并回 skillParams（如 company_name, credit_code）
                            // 避免下一轮参数丢失导致技能重新从阶段一/二开始
                            if (result.containsKey("company_name")) {
                                skillParams.put("company_name", result.get("company_name"));
                            }
                            if (result.containsKey("credit_code")) {
                                skillParams.put("credit_code", result.get("credit_code"));
                            }
                            // 保存待处理技能上下文，下一条用户消息将直接回到此技能
                            contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                            log.info("Pending skill set: {} (info_needed), params: {}", skillName, skillParams);
                        } else if ("result".equals(action) || "not_found".equals(action)) {
                            String eventType = switch (skillName) {
                                case "query_due_diligence_reports" -> "historical_dd_query_result";
                                case "verify_business_license" -> "information_check_result";
                                case "generate_report" -> "report_generate_result";
                                case "query_company_basic_info", "query_shareholder_info", "query_beneficiary_info",
                                     "query_company_genealogy", "query_customs_auth", "query_customs_blacklist",
                                     "query_account_freeze_tag", "query_credit_granting",
                                     "query_pboc_account_control" -> "company_query_result";
                                default -> "risk_check_result";
                            };
                            // 将 skill_name 注入到结果中，方便前端根据技能类型路由卡片
                            result.put("_skill_name", skillName);
                            if (multiIndex >= 0) result.put("_multi_index", multiIndex);
                            eventFlux = Flux.just(sseEvent(eventType, result, assistantMsgId, null));
                            // 任务完成事件：多意图管道内的任务执行完毕时通知前端将该任务
                            // 标记为已完成（解除等待补充信息的暂停状态），让"第 X 项任务已完成"
                            // 的进度反馈及时出现，而非等整条流结束时由 done 一次性收尾。
                            // 例外：模板选择阶段（stage=templates）与跳转阶段（stage=redirect）
                            // 任务均未真正完成（报告尚未生成），不发 task_done
                            if (!templateStage && !redirectStage && (multiIndex >= 0
                                    || (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty()))) {
                                Map<String, Object> taskDoneData = new LinkedHashMap<>();
                                // order 计算：管道内直接取 multiIndex + 1；但模板选择恢复路径
                                // handleSkill 以 multiIndex=-1 重入且 pipelinePlan 非空（暂停时
                                // 完整快照仍在），按 multiIndex + 1 会发出 order=0 的错误事件，
                                // 需按 skill 反查全局序号
                                int doneOrder = multiIndex + 1;
                                if (multiIndex < 0 && ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty()) {
                                    for (Map<String, Object> item : ctx.pipelinePlan) {
                                        if (skillName.equals(item.get("skill"))) {
                                            doneOrder = (int) item.getOrDefault("order", multiIndex + 1);
                                            break;
                                        }
                                    }
                                }
                                taskDoneData.put("order", doneOrder);
                                taskDoneData.put("skill", skillName);
                                taskDoneData.put("label", skillRegistry.getSkillLabel(skillName));
                                eventFlux = eventFlux.concatWith(
                                        Flux.just(sseEvent("task_done", taskDoneData, null, convId)));
                            }
                            // 模板选择阶段：保存待处理技能，使 executePipeline 检测到
                            // hasPendingSkill 后记录剩余任务并停止续跑；下一条
                            // 【模板选择】<template_id> 消息将重入本技能注入模板 ID
                            if (templateStage) {
                                contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                                log.info("Pending skill set: {} (template selection)", skillName);
                                // 多意图管道内等待模板选择同样属于"暂停"：追加 pipeline_paused 事件，
                                // 前端据此把任务清单卡标记 paused，避免本条 SSE 流结束时 done 事件
                                // 误判"currentOrder >= total"而弹出"N 项任务已完成"完成卡
                                if (multiIndex >= 0
                                        || (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty())) {
                                    Map<String, Object> pausedData = new LinkedHashMap<>();
                                    pausedData.put("hint", result.getOrDefault("message", "请选择报告模板"));
                                    eventFlux = eventFlux.concatWith(
                                            Flux.just(sseEvent("pipeline_paused", pausedData, null, convId)));
                                }
                            }
                            // 跳转阶段（stage=redirect）：任务挂起等待 H5 异步报告生成。
                            // 设置 waitingReportTask 使 executePipeline 的 defer 检测到后保存
                            // 剩余任务并暂停续跑；报告生成完成后前端轮询到 completed 调用
                            // report-completed 接口推进管道（而非用户消息 resume）
                            if (redirectStage) {
                                // 管道判断兜底：multiIndex>=0 直接成立；pendingSkill 重入路径
                                // （multiIndex=-1）时内存快照 pipelinePlan 可能为空，用 snapshotPlan
                                // 从对话历史恢复完整计划，避免 waitingReportTask 漏设导致
                                // report-completed 走 skipped 幂等分支、管道永不推进
                                List<Map<String, Object>> planSnapshot = snapshotPlan(convId, conv);
                                if (multiIndex >= 0
                                        || (planSnapshot != null && !planSnapshot.isEmpty())) {
                                    Map<String, Object> waitingTask = new LinkedHashMap<>();
                                    waitingTask.put("skill", skillName);
                                    waitingTask.put("label", skillRegistry.getSkillLabel(skillName));
                                    // 序号同样按 skill 反查全局 order（multiIndex=-1 恢复路径）
                                    int reportOrder = multiIndex + 1;
                                    if (multiIndex < 0 && planSnapshot != null && !planSnapshot.isEmpty()) {
                                        for (Map<String, Object> item : planSnapshot) {
                                            if (skillName.equals(item.get("skill"))) {
                                                reportOrder = (int) item.getOrDefault("order", reportOrder);
                                                break;
                                            }
                                        }
                                    }
                                    waitingTask.put("order", reportOrder);
                                    contextMemoryService.setWaitingReportTask(convId, waitingTask);
                                    log.info("Waiting report task set: {} (order {}), pipeline suspended",
                                            skillName, reportOrder);
                                    // 追加 pipeline_paused 事件，避免本条 SSE 流结束时 done 事件
                                    // 误判"currentOrder >= total"而弹出"N 项任务已完成"完成卡
                                    Map<String, Object> pausedData = new LinkedHashMap<>();
                                    pausedData.put("hint", "报告将在编辑页面生成，生成完成后将自动继续");
                                    eventFlux = eventFlux.concatWith(
                                            Flux.just(sseEvent("pipeline_paused", pausedData, null, convId)));
                                }
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

                        // 清理待处理技能（技能已完成或未找到结果），并清除已使用的附件。
                        // 例外：模板选择阶段（stage=templates）任务尚未完成，保留 pendingSkill
                        // 等待用户点击模板后重入，此处不能清理
                        if (("result".equals(action) || "not_found".equals(action)) && !templateStage) {
                            contextMemoryService.clearPendingSkill(convId);
                            contextMemoryService.clearAttachment(convId);
                        }
                        // reset或result/not_found时重置重试计数（新技能调用从0开始）
                        if (!"candidates".equals(action) && !"ambiguous".equals(action) && !"info_needed".equals(action)) {
                            ctx.pendingSkillRetry = 0;
                        }

                        // 存储助手消息（同步，顺序执行）
                        try {
                            String summaryText = mapper.writeValueAsString(result);
                            Message asstMsg = new Message(assistantMsgId, "assistant", summaryText, Instant.now().toString());
                            conv.getMessages().add(asstMsg);
                            conv.setUpdatedAt(asstMsg.getCreatedAt());
                            // 消息追加后立即落盘（与用户消息存储处一致）
                            conversationService.persist();
                        } catch (Exception e) {
                            log.error("Failed to serialize result: {}", e.getMessage());
                        }

                        // 跟踪技能调用 + 后续建议（模板选择阶段不算完成，跳过）
                        if ("result".equals(action) && !templateStage) {
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
                        conv.getMessages().add(asstMsg);
                        conv.setUpdatedAt(asstMsg.getCreatedAt());
                        // 流式生成结束/被强制终止时保存已生成内容，随后立即落盘
                        conversationService.persist();
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

    /**
     * 处理意图澄清决策：发送 intent_candidates 事件
     */
    @SuppressWarnings("unchecked")
    private Flux<String> handleClarify(Map<String, Object> decision, String convId, Conversation conv) {
        String assistantMsgId = UUID.randomUUID().toString();
        String message = (String) decision.getOrDefault("message", "您的问题可能有多种理解，请选择您想要的操作：");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) decision.getOrDefault("candidates", List.of());

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("message", message);
        eventData.put("candidates", candidates);

        // 存储助手消息
        try {
            String summaryText = mapper.writeValueAsString(eventData);
            Message asstMsg = new Message(assistantMsgId, "assistant", summaryText, Instant.now().toString());
            conv.getMessages().add(asstMsg);
            conv.setUpdatedAt(asstMsg.getCreatedAt());
            // 消息追加后立即落盘（与用户消息存储处一致）
            conversationService.persist();
        } catch (Exception e) {
            log.error("Failed to serialize clarify result: {}", e.getMessage());
        }

        return Flux.just(
                sseEvent("meta", Map.of("conversation_id", convId), assistantMsgId, null),
                sseEvent("intent_candidates", eventData, assistantMsgId, null)
        );
    }

    /**
     * 处理多意图决策：生成执行计划并顺序执行
     */
    @SuppressWarnings("unchecked")
    private Flux<String> handleMulti(Map<String, Object> decision, String convId,
                                     String userId, Conversation conv) {
        List<Map<String, Object>> skills = (List<Map<String, Object>>) decision.getOrDefault("skills", List.of());
        List<TaskPlanner.PlanTask> plan = taskPlanner.plan(skills);

        if (plan.isEmpty()) {
            return handleChat(convId, conv, "");
        }

        String planText = taskPlanner.buildPlanText(plan);
        log.info("Multi-intent plan: {} tasks, text: {}", plan.size(), planText);

        // 保存计划快照（含 label/order），供暂停恢复时重建 planning 事件（前端任务清单）
        List<Map<String, Object>> planSnapshot = new ArrayList<>();
        for (TaskPlanner.PlanTask t : plan) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skill", t.skill());
            item.put("label", skillRegistry.getSkillLabel(t.skill()));
            item.put("order", t.order());
            planSnapshot.add(item);
        }
        contextMemoryService.get(convId).pipelinePlan = planSnapshot;

        // 发送规划文本 + 顺序执行每个任务
        String assistantMsgId = UUID.randomUUID().toString();
        Map<String, Object> planningData = new LinkedHashMap<>();
        planningData.put("plan", planSnapshot);
        planningData.put("text", planText);
        // resume=false 表示首次规划（前端据此新建任务清单卡片；true 为暂停恢复时更新已有卡片）
        planningData.put("resume", false);

        // 将任务清单作为可见消息持久化（与其他结果卡片一致），
        // 这样切换会话/刷新后任务清单仍保留在对话流中，不会因管道结束而消失
        try {
            Map<String, Object> planMsgData = new LinkedHashMap<>();
            planMsgData.put("action", "pipeline");
            planMsgData.put("kind", "plan");
            planMsgData.put("plan", planSnapshot);
            planMsgData.put("total", planSnapshot.size());
            planMsgData.put("currentOrder", 0);
            planMsgData.put("paused", false);
            planMsgData.put("text", planText);
            String planSummary = mapper.writeValueAsString(planMsgData);
            Message planMsg = new Message(UUID.randomUUID().toString(), "assistant",
                    planSummary, Instant.now().toString());
            conv.getMessages().add(planMsg);
            conv.setUpdatedAt(planMsg.getCreatedAt());
            // 任务清单消息追加后立即落盘
            conversationService.persist();
        } catch (Exception e) {
            log.error("Failed to serialize pipeline plan: {}", e.getMessage());
        }

        Flux<String> planEvent = Flux.just(
                sseEvent("meta", Map.of("conversation_id", convId), assistantMsgId, null),
                sseEvent("planning", planningData, assistantMsgId, null),
                sseEvent("text_delta", Map.of("content", planText), assistantMsgId, null),
                sseEvent("text_done", Map.of("content", planText), assistantMsgId, null)
        );

        return planEvent.concatWith(executePipeline(plan, 0, convId, userId, conv));
    }

    /**
     * 恢复多意图管道（从 pendingPipeline 中继续）
     */
    private Flux<String> handleMultiResume(String convId, String userId, Conversation conv, String userMessage) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);

        // 先完成当前暂停的任务
        String pendingSkill = ctx.pendingSkillName;
        Map<String, Object> pendingParams = new LinkedHashMap<>(ctx.pendingSkillParams);
        pendingParams.put("_user_input", userMessage);

        Map<String, Object> currentDecision = new LinkedHashMap<>();
        currentDecision.put("action", "skill");
        currentDecision.put("skill", pendingSkill);
        currentDecision.put("params", pendingParams);
        currentDecision.put("reason", "恢复管道当前任务: " + pendingSkill);
        contextMemoryService.clearPendingSkill(convId);

        // 恢复计划清单：优先用暂停时保存的完整快照（含已完成任务、label），
        // 其次从对话历史中最近一条规划消息恢复完整 plan（后端重启等内存快照丢失场景），
        // 最后兑底从 pendingPipeline 重建（只含剩余任务，此时前端 task_start 会以
        // 已有清单兑底，避免"第 1/1 项「第 1 项任务」"标签错乱与总数缩水）
        List<Map<String, Object>> planSnapshot = ctx.pipelinePlan;
        if (planSnapshot == null || planSnapshot.isEmpty()) {
            planSnapshot = findPipelinePlanFromHistory(conv);
        }
        if (planSnapshot == null || planSnapshot.isEmpty()) {
            planSnapshot = new ArrayList<>();
            for (Map<String, Object> task : ctx.pendingPipeline) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("skill", task.get("skill"));
                item.put("label", skillRegistry.getSkillLabel((String) task.get("skill")));
                item.put("order", task.getOrDefault("order", 0));
                planSnapshot.add(item);
            }
        }

        // 本次恢复执行的是 pendingSkill（暂停的任务）：其序号需从完整计划快照中按 skill 反查，
        // 而不是取 pendingPipeline 首项（那是剩余任务，序号靠后），否则前端会把尚未完成的
        // 暂停任务错标为已完成、任务进度卡提前跳到后续任务
        int currentIndex = 0;
        int currentOrder = currentIndex + 1;
        for (int i = 0; i < planSnapshot.size(); i++) {
            if (pendingSkill.equals(planSnapshot.get(i).get("skill"))) {
                currentIndex = i;
                currentOrder = (int) planSnapshot.get(i).getOrDefault("order", i + 1);
                break;
            }
        }

        String planText = buildPlanTextFromSnapshot(planSnapshot);
        Map<String, Object> planningData = new LinkedHashMap<>();
        planningData.put("plan", planSnapshot);
        planningData.put("text", planText);
        // 恢复路径：前端应更新已有任务清单卡片（而非新建），故标记 resume=true
        planningData.put("resume", true);

        Map<String, Object> taskStartData = new LinkedHashMap<>();
        taskStartData.put("index", currentOrder);
        taskStartData.put("total", planSnapshot.size());
        taskStartData.put("skill", pendingSkill);
        taskStartData.put("label", skillRegistry.getSkillLabel(pendingSkill));
        taskStartData.put("order", currentOrder);

        Flux<String> resumeEvents = Flux.just(
                sseEvent("planning", planningData, null, convId),
                sseEvent("task_start", taskStartData, null, convId)
        );

        Flux<String> currentTask = handleSkill(currentDecision, convId, userId, conv, currentIndex);

        // 完成后检查并执行剩余任务
        return resumeEvents.concatWith(currentTask).concatWith(Flux.defer(() -> {
            ContextMemoryService.ConversationContext ctx2 = contextMemoryService.get(convId);
            // 恢复的任务再次返回信息缺失（如用户只选择了企业但尚未上传附件）：
            // 必须停止续跑、保留剩余任务队列（pendingPipeline 不清空），发送暂停事件等待
            // 用户补齐信息后下一条消息再次 resume。若先执行剩余任务，pendingSkill 会被
            // 后续任务覆盖，导致"请上传营业执照"等补充机会被跳过、直接跳到下一个任务
            if (ctx2.hasPendingSkill() || ctx2.isWaitingReport()) {
                Map<String, Object> pausedData = new LinkedHashMap<>();
                pausedData.put("hint", ctx2.pendingInputHint);
                return Flux.just(sseEvent("pipeline_paused", pausedData, null, convId));
            }
            if (ctx2.hasPendingPipeline()) {
                List<Map<String, Object>> remaining = new ArrayList<>(ctx2.pendingPipeline);
                ctx2.pendingPipeline.clear();
                List<TaskPlanner.PlanTask> remainingPlan = new ArrayList<>();
                for (Map<String, Object> task : remaining) {
                    remainingPlan.add(new TaskPlanner.PlanTask(
                            (String) task.get("skill"),
                            task.get("params") instanceof Map ? (Map<String, Object>) task.get("params") : new LinkedHashMap<>(),
                            (int) task.getOrDefault("order", 0),
                            null, List.of()));
                }
                return executePipeline(remainingPlan, 0, convId, userId, conv);
            }
            return Flux.empty();
        }));
    }

    /**
     * 顺序执行管道中的任务
     */
    private Flux<String> executePipeline(List<TaskPlanner.PlanTask> plan, int startIndex,
                                         String convId, String userId, Conversation conv) {
        if (startIndex >= plan.size()) {
            // 管道全部任务执行完毕，清理计划快照（任务暂停时会先返回 Flux.empty()，不会走到这里）
            ContextMemoryService.ConversationContext ctxDone = contextMemoryService.get(convId);
            // 先取出完整计划快照用于持久化完成卡，再清理内存快照
            List<Map<String, Object>> fullPlan = null;
            if (ctxDone.pipelinePlan != null && !ctxDone.pipelinePlan.isEmpty()) {
                fullPlan = new ArrayList<>(ctxDone.pipelinePlan);
            }
            if (!ctxDone.hasPendingSkill()) {
                ctxDone.pipelinePlan.clear();
            }
            if (fullPlan == null || fullPlan.isEmpty()) {
                fullPlan = findPipelinePlanFromHistory(conv);
            }
            // 全部完成：持久化最终完成卡（kind=complete），使"N 项任务已完成"闭环
            // 在切换会话/刷新后仍保留在对话流中（与前端 done 事件新建完成卡一致）
            if (fullPlan != null && !fullPlan.isEmpty()) {
                Map<String, Object> completeCard = new LinkedHashMap<>();
                completeCard.put("action", "pipeline");
                completeCard.put("kind", "complete");
                completeCard.put("plan", fullPlan);
                completeCard.put("total", fullPlan.size());
                completeCard.put("currentOrder", fullPlan.size());
                completeCard.put("paused", false);
                completeCard.put("completed", true);
                persistPipelineCard(conv, completeCard);
                // 全部完成：将历史中所有 plan/switch 卡标记为完成态，避免切换会话/
                // 刷新后这些卡片仍按 currentOrder 显示"进行中"（与末尾完成卡矛盾）
                markPipelineCardsCompleted(conv);
            }
            return Flux.empty();
        }

        TaskPlanner.PlanTask task = plan.get(startIndex);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "skill");
        decision.put("skill", task.skill());
        decision.put("params", new LinkedHashMap<>(task.params()));
        decision.put("reason", "管道任务 " + task.order());

        // 任务开始事件：前端据此更新"当前任务 x/y"进度指示。
        // total 必须是完整计划的任务总数（而非传入 plan 的大小）：暂停恢复时
        // handleMultiResume 传入的 remainingPlan 只含剩余任务，若按 plan.size()
        // 计算会让 task_start.total 缩水（如 2 → 1），前端任务切换卡/完成卡会按
        // 错误总数渲染（正在执行的任务被隐藏、"N 项任务已完成"数量错误）。
        // plan 中任务的 order 是完整计划的全局连续编号（从 1 起），剩余计划必含
        // 原计划尾部任务，order 最大值即总任务数，不依赖可能丢失/缩水的内存
        // 快照 pipelinePlan（后端重启或快照被清理后无法还原完整计划）
        int totalTasks = plan.size();
        for (TaskPlanner.PlanTask t : plan) {
            totalTasks = Math.max(totalTasks, t.order());
        }
        ContextMemoryService.ConversationContext ctxTotal = contextMemoryService.get(convId);
        if (ctxTotal.pipelinePlan != null && ctxTotal.pipelinePlan.size() > totalTasks) {
            totalTasks = ctxTotal.pipelinePlan.size();
        }
        Map<String, Object> taskStartData = new LinkedHashMap<>();
        taskStartData.put("index", task.order());
        taskStartData.put("total", totalTasks);
        taskStartData.put("skill", task.skill());
        taskStartData.put("label", skillRegistry.getSkillLabel(task.skill()));
        taskStartData.put("order", task.order());
        Flux<String> taskStartEvent = Flux.just(sseEvent("task_start", taskStartData, null, convId));

        // 管道进度持久化：更新初始执行计划卡（首卡）的 currentOrder，使切换会话/刷新后
        // 首卡仍反映最新执行进度；进入新任务（order>1）时追加任务切换卡，使其保留在对话流中
        updatePipelinePlanCardOrder(conv, task.order());
        List<Map<String, Object>> planSnapshot = snapshotPlan(convId, conv);
        if (task.order() > 1 && planSnapshot != null && !planSnapshot.isEmpty()) {
            Map<String, Object> switchCard = new LinkedHashMap<>();
            switchCard.put("action", "pipeline");
            switchCard.put("kind", "switch");
            switchCard.put("plan", planSnapshot);
            switchCard.put("total", totalTasks);
            switchCard.put("currentOrder", task.order());
            switchCard.put("paused", false);
            persistPipelineCard(conv, switchCard);
        }

        // multiIndex 必须是任务在完整计划中的 0-based 下标（全局 order - 1）：
        // handleSkill 依据它生成 task_done 事件的 order（multiIndex + 1）与结果卡
        // _multi_index；若传 remainingPlan 的局部下标（暂停恢复后从 0 起），
        // risk 等后续任务会被错标为第 1 个任务，前端进度卡无法正确推进
        Flux<String> taskFlux = taskStartEvent.concatWith(handleSkill(decision, convId, userId, conv, task.order() - 1));

        // 检查是否暂停（pendingSkill 被设置），如果暂停则记录剩余管道
        return taskFlux.concatWith(Flux.defer(() -> {
            ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
            if (ctx.hasPendingSkill() || ctx.isWaitingReport()) {
                // 记录剩余任务到 pendingPipeline
                List<Map<String, Object>> remaining = new ArrayList<>();
                for (int i = startIndex + 1; i < plan.size(); i++) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("skill", plan.get(i).skill());
                    t.put("params", plan.get(i).params());
                    t.put("order", plan.get(i).order());
                    t.put("_index", i);
                    remaining.add(t);
                }
                ctx.pendingPipeline.addAll(remaining);
                log.info("Pipeline paused at task {}, remaining {} tasks saved to pendingPipeline",
                        startIndex, remaining.size());
                // 暂停事件：仅标记前端卡片暂停状态（hint 保留供未来扩展），
                // 具体补充提示（如"请上传该企业的营业执照图片"）由 handleSkill 的
                // text_delta/text_done 以文本气泡返回，提示只出现在对话流一处
                Map<String, Object> pausedData = new LinkedHashMap<>();
                pausedData.put("hint", ctx.pendingInputHint);
                return Flux.just(sseEvent("pipeline_paused", pausedData, null, convId));
            }
            // 继续下一个任务
            return executePipeline(plan, startIndex + 1, convId, userId, conv);
        }));
    }

    // ---------- SSE 辅助方法 ----------

    /**
     * 从对话历史中恢复最近一次完整任务计划快照（含 label/order）。
     * 多意图管道首次规划时 handleMulti 会把完整 plan 持久化为 assistant 消息
     * （content 为 {"action":"pipeline","plan":[{skill,label,order},...]}）。
     * 暂停恢复时若内存快照 pipelinePlan 已丢失（如后端重启），从历史中恢复完整计划，
     * 避免兑底重建（只含剩余任务、label 缺失）导致前端任务总数缩水、
     * 任务标签显示"第 X 项任务"占位、已完成任务数错乱。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findPipelinePlanFromHistory(Conversation conv) {
        List<Message> messages = conv.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!"assistant".equals(msg.getRole())) continue;
            try {
                Map<String, Object> content = mapper.readValue(msg.getContent(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                if (content != null && "pipeline".equals(content.get("action"))) {
                    Object planObj = content.get("plan");
                    if (planObj instanceof List && !((List<?>) planObj).isEmpty()) {
                        return (List<Map<String, Object>>) planObj;
                    }
                }
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息（如普通文本回复）直接跳过
            }
        }
        return null;
    }

    /**
     * 从计划快照（含 label/order 的 Map 列表）生成规划文本，格式与 TaskPlanner.buildPlanText 一致
     */
    private String buildPlanTextFromSnapshot(List<Map<String, Object>> planSnapshot) {
        if (planSnapshot == null || planSnapshot.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("我将依次为您执行：");
        String[] numbers = {"①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩"};
        for (int i = 0; i < planSnapshot.size(); i++) {
            Object label = planSnapshot.get(i).get("label");
            sb.append(numbers[i < numbers.length ? i : 0]).append(" ").append(label);
            if (i < planSnapshot.size() - 1) sb.append(" ");
        }
        return sb.toString();
    }

    /**
     * 获取当前管道的完整任务快照（含 label/order）：优先用内存快照 pipelinePlan，
     * 丢失时（如后端重启）从会话历史中恢复初始规划卡上的完整计划。
     */
    private List<Map<String, Object>> snapshotPlan(String convId, Conversation conv) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty()) {
            return new ArrayList<>(ctx.pipelinePlan);
        }
        return findPipelinePlanFromHistory(conv);
    }

    /**
     * 将管道进度卡片（任务切换卡/完成卡）持久化为 assistant 消息并落盘，
     * 使这些卡片在切换会话或刷新后仍保留在对话流中。
     */
    private void persistPipelineCard(Conversation conv, Map<String, Object> cardData) {
        try {
            String json = mapper.writeValueAsString(cardData);
            Message msg = new Message(UUID.randomUUID().toString(), "assistant", json, Instant.now().toString());
            conv.getMessages().add(msg);
            conv.setUpdatedAt(msg.getCreatedAt());
            conversationService.persist();
        } catch (Exception e) {
            log.error("Failed to persist pipeline card: {}", e.getMessage());
        }
    }

    /**
     * 更新已持久化的初始执行计划卡（首卡，kind 非 switch/complete）的 currentOrder，
     * 使切换会话/刷新后首卡仍反映最新执行进度（与前端 task_start 同步首卡逻辑一致）。
     */
    @SuppressWarnings("unchecked")
    private void updatePipelinePlanCardOrder(Conversation conv, int currentOrder) {
        List<Message> messages = conv.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!"assistant".equals(msg.getRole())) continue;
            try {
                Map<String, Object> content = mapper.readValue(msg.getContent(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                if (content != null && "pipeline".equals(content.get("action"))
                        && !"switch".equals(content.get("kind")) && !"complete".equals(content.get("kind"))) {
                    content.put("currentOrder", currentOrder);
                    msg.setContent(mapper.writeValueAsString(content));
                    conversationService.persist();
                    return;
                }
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息（如普通文本回复）直接跳过
            }
        }
    }

    /**
     * 管道全部完成后，将历史中所有任务进度卡（plan/switch，即 kind != complete）
     * 标记为已完成（completed=true），使切换会话/刷新后这些卡片显示"已完成"态，
     * 而非按 currentOrder 显示"进行中"，与末尾绿色完成卡语义一致。
     */
    @SuppressWarnings("unchecked")
    private void markPipelineCardsCompleted(Conversation conv) {
        boolean changed = false;
        for (Message msg : conv.getMessages()) {
            if (!"assistant".equals(msg.getRole())) continue;
            try {
                Map<String, Object> content = mapper.readValue(msg.getContent(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                if (content != null && "pipeline".equals(content.get("action"))
                        && !"complete".equals(content.get("kind"))) {
                    content.put("completed", true);
                    msg.setContent(mapper.writeValueAsString(content));
                    changed = true;
                }
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息（如普通文本回复）直接跳过
            }
        }
        if (changed) {
            conversationService.persist();
        }
    }

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