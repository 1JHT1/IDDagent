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

    private final ConversationService conversationService;
    private final ContextMemoryService contextMemoryService;
    private final CoordinatorService coordinatorService;
    private final FollowUpService followUpService;
    private final AgentService agentService;
    private final SkillRegistry skillRegistry;

    public ChatController(ConversationService conversationService,
                          ContextMemoryService contextMemoryService,
                          CoordinatorService coordinatorService,
                          FollowUpService followUpService,
                          AgentService agentService,
                          SkillRegistry skillRegistry) {
        this.conversationService = conversationService;
        this.contextMemoryService = contextMemoryService;
        this.coordinatorService = coordinatorService;
        this.followUpService = followUpService;
        this.agentService = agentService;
        this.skillRegistry = skillRegistry;
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

        Message userMsg = new Message(userMsgId, "user", body.getMessage(), now);
        userMsg.setAttachments(attachments);
        conv.getMessages().add(userMsg);
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

        // 检查是否有待处理技能（上次技能正在等待用户补充信息）
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        boolean hasPendingSkill = ctx.hasPendingSkill();

        // 初始事件：pending skill 无需 LLM 分析，改为更准确的提示
        String thinkingText = hasPendingSkill ? "正在查询，请稍候..." : "正在分析您的问题...";
        Flux<String> initEvent = Flux.just(sseEvent("thinking",
                Map.of("content", thinkingText), null, convId));

        // 主流程
        Flux<String> mainFlow;
        if (hasPendingSkill) {
            // 检查重试上限：防止 pending skill 死循环（如参数丢失导致永不到达 result）
            if (ctx.pendingSkillRetry >= 3) {
                log.warn("Pending skill {} exceeded retry limit, clearing and falling back to Coordinator",
                        ctx.pendingSkillName);
                contextMemoryService.clearPendingSkill(convId);
                mainFlow = coordinatorService.routeIntent(finalMessage, finalConv.getMessages())
                        .flatMapMany(decision -> {
                            if ("skill".equals(decision.get("action"))) {
                                return handleSkill(decision, convId, userId, finalConv);
                            } else {
                                return handleChat(convId, finalConv, finalMessage);
                            }
                        });
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
                mainFlow = handleSkill(decision, convId, userId, finalConv);
            }
        } else {
            // 无待处理技能 → 走 Coordinator 意图识别（携带对话历史以便 LLM 理解上下文）
            mainFlow = coordinatorService.routeIntent(finalMessage, finalConv.getMessages())
                    .flatMapMany(decision -> {
                        if ("skill".equals(decision.get("action"))) {
                            return handleSkill(decision, convId, userId, finalConv);
                        } else {
                            return handleChat(convId, finalConv, finalMessage);
                        }
                    });
        }

        return initEvent.concatWith(mainFlow)
                // 所有事件流结束后发送 done 事件
                .concatWith(Flux.just(sseEvent("done", Map.of("conversation_id", convId), null, convId)))
                .doOnSubscribe(s -> System.out.println("🔵 SSE Flux 被订阅!"))
                //.doOnNext(event -> System.out.println("📤 发送 SSE: " + event.substring(0, Math.min(120, event.length()))))
                .doOnComplete(() -> System.out.println("✅ SSE Flux 完成"))
                .doOnError(e -> log.error("Stream error", e))
                .onErrorResume(e -> Flux.just(sseEvent("error",
                        Map.of("content", "处理请求失败: " + e.getMessage()), null, null)));
    }

    /**
     * 处理技能分支（非阻塞）
     */
    private Flux<String> handleSkill(Map<String, Object> decision, String convId,
                                     String userId, Conversation conv) {
        String skillName = (String) decision.getOrDefault("skill", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> skillParams = new LinkedHashMap<>(
                (Map<String, Object>) decision.getOrDefault("params", Map.of()));

        // 上下文记忆补全
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (!ctx.isEmpty()) {
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

        log.info("Coordinator routed to skill: {}, params: {}", skillName, skillParams);
        skillParams.put("_conversation_id", convId);

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
                    } else {
                        String action = (String) result.getOrDefault("action", "");
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
                            // 保存待处理技能上下文，下一条用户消息将直接回到此技能
                            contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                            log.info("Pending skill set: {} (candidates)", skillName);
                        } else if ("info_needed".equals(action)) {
                            String prompt = (String) result.getOrDefault("message", "");
                            eventFlux = Flux.just(
                                    sseEvent("text_delta", Map.of("content", prompt), assistantMsgId, null),
                                    sseEvent("text_done", Map.of("content", prompt), assistantMsgId, null)
                            );
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
                        } else if ("result".equals(action) || "ambiguous".equals(action) || "not_found".equals(action)) {
                            String eventType = switch (skillName) {
                                case "prepare_customer_outreach" -> "outreach_result";
                                case "recommend_products" -> "product_recommend_result";
                                case "match_products_intelligently" -> "product_match_result";
                                case "open_corporate_account" -> "account_opening_result";
                                case "query_due_diligence_reports" -> "historical_dd_query_result";
                                default -> "risk_check_result";
                            };
                            // 将 skill_name 注入到结果中，方便前端根据技能类型路由卡片
                            result.put("_skill_name", skillName);
                            eventFlux = Flux.just(sseEvent(eventType, result, assistantMsgId, null));
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

                        // 清理待处理技能（技能已完成或未找到结果）
                        if ("result".equals(action) || "not_found".equals(action)) {
                            contextMemoryService.clearPendingSkill(convId);
                        }
                        // reset或result/not_found时重置重试计数（新技能调用从0开始）
                        if (!"candidates".equals(action) && !"info_needed".equals(action)) {
                            ctx.pendingSkillRetry = 0;
                        }

                        // 存储助手消息（同步，顺序执行）
                        try {
                            String summaryText = mapper.writeValueAsString(result);
                            Message asstMsg = new Message(assistantMsgId, "assistant", summaryText, Instant.now().toString());
                            conv.getMessages().add(asstMsg);
                            conv.setUpdatedAt(asstMsg.getCreatedAt());
                        } catch (Exception e) {
                            log.error("Failed to serialize result: {}", e.getMessage());
                        }

                        // 跟踪技能调用 + 后续建议
                        if ("result".equals(action)) {
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
                .doOnComplete(() -> {
                    // 存储助手消息（完整内容）
                    if (fullContent.length() > 0) {
                        Message asstMsg = new Message(assistantMsgId, "assistant",
                                fullContent.toString(), Instant.now().toString());
                        conv.getMessages().add(asstMsg);
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