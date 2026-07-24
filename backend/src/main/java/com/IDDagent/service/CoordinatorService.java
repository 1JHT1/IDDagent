package com.IDDagent.service;

import com.IDDagent.config.AppConfig;
import com.IDDagent.model.Message;
import com.IDDagent.skill.SkillRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");

    private final SkillRegistry skillRegistry;
    private final WebClient webClient;
    private final AppConfig config;

    public CoordinatorService(SkillRegistry skillRegistry, WebClient webClient, AppConfig config) {
        this.skillRegistry = skillRegistry;
        this.webClient = webClient;
        this.config = config;
    }

    /**
     * 路由意图（无历史消息，基础版）
     */
    public Mono<Map<String, Object>> routeIntent(String userMessage) {
        return routeIntent(userMessage, List.of());
    }

    /**
     * 路由意图（携带对话历史，非阻塞响应式）
     * @param userMessage 用户当前输入
     * @param messages    最近对话消息列表（用于 LLM 理解上下文）
     * @return Mono<Map<String, Object>> 决策结果
     */
    public Mono<Map<String, Object>> routeIntent(String userMessage, List<Message> messages) {
        String systemPrompt = buildSystemPrompt();
        String apiKey = config.getDeepseek().getApiKey();
        String baseUrl = config.getDeepseek().getBaseUrl();
        String model = config.getModel().getCoordinator();

        // API key 缺失，直接返回 fallback（同步快速）
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DEEPSEEK_API_KEY not set, defaulting to chat");
            return Mono.just(fallbackMap("API key not configured"));
        }

        // 构建请求体：包含对话历史以帮助 LLM 理解上下文
        List<Map<String, Object>> chatMessages = new ArrayList<>();
        chatMessages.add(Map.of("role", "system", "content", systemPrompt));

        // 添加最近对话历史（最多最近 6 条，取最后 3 对 user/assistant）
        if (messages != null && !messages.isEmpty()) {
            int startIdx = Math.max(0, messages.size() - 6);
            for (int i = startIdx; i < messages.size(); i++) {
                Message msg = messages.get(i);
                chatMessages.add(Map.of(
                        "role", msg.getRole(),
                        "content", msg.getContent()
                ));
            }
        }

        // 当前用户消息
        chatMessages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", chatMessages);
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 300);

        // 打印调试信息（非阻塞）
        try {
            System.out.println("===== 完整请求 URL: " + baseUrl + "/v1/chat/completions");
            System.out.println("===== 请求体 JSON: " + mapper.writeValueAsString(requestBody));
            System.out.println("===== Authorization 头: Bearer " + apiKey.substring(0, 10) + "...");
        } catch (Exception e) {
            // 忽略打印异常
        }

        // 发起非阻塞调用，并处理结果
        return webClient.post()
                .uri(baseUrl + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseResponse)          // 解析响应
                .onErrorResume(e -> {              // 错误时降级
                    log.error("Coordinator LLM call failed: {}", e.getMessage());
                    return Mono.just(fallbackMap("意图识别请求失败"));
                });
    }

    /**
     * 解析 DeepSeek 返回的文本，提取决策 JSON
     */
    private Map<String, Object> parseResponse(String response) {
        try {
            Map<String, Object> respMap = mapper.readValue(response, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            String text = "";
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                text = (String) message.getOrDefault("content", "");
            }

            Matcher jsonMatch = JSON_PATTERN.matcher(text);
            if (jsonMatch.find()) {
                Map<String, Object> decision = mapper.readValue(jsonMatch.group(), new TypeReference<>() {});
                String action = (String) decision.get("action");
                if ("skill".equals(action) || "chat".equals(action)) {
                    if ("skill".equals(action)) {
                        String skillName = (String) decision.getOrDefault("skill", "");
                        if (skillRegistry.get(skillName) == null) {
                            log.warn("Coordinator returned unknown skill '{}', falling back to chat", skillName);
                            return fallbackMap("意图识别返回未知技能");
                        }
                    }
                    log.info("Coordinator intent: {}, reason: {}", decision.get("action"), decision.getOrDefault("reason", "unknown"));
                    return decision;
                }
            }
            // 未匹配到合法 JSON 或 action 不正确
            log.warn("No valid decision JSON found in response: {}", text);
            return fallbackMap("未提取到有效意图");

        } catch (Exception e) {
            log.warn("JSON parse error: {}", e.getMessage());
            return fallbackMap("意图识别解析失败");
        }
    }

    /**
     * 构建 fallback 决策（普通聊天模式）
     */
    private Map<String, Object> fallbackMap(String reason) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("action", "chat");
        fallback.put("reason", reason);
        return fallback;
    }

    private String buildSystemPrompt() {
        String skillsPrompt = skillRegistry.getSkillsPrompt();
        return """
                你是一个任务规划主控智能体。分析用户输入（含对话历史上下文），判断意图并做出路由决策。

                ## 上下文记忆
                系统维护了当前会话的上下文记忆（最近操作的企业主体）。即使用户没有在当前消息中明确提及企业名称，只要意图明确（如「帮我准备拓户材料」「推荐产品」「查下风险」），你仍然应该路由到对应的技能。系统会自动从上下文记忆中补充缺失的企业参数。

                ## 对话历史
                以下是当前会话的最近对话历史（user/assistant 消息）。请结合历史消息理解用户意图：
                - 如果用户说"换一家"、"再看另一家"、"查另一家"等 → 表示想切换企业，应匹配到最近使用的同类型技能
                - 如果用户说"再查2024年的"、"换个时间"等 → 表示想变更查询条件，应匹配到最近使用的同类型技能
                - 如果用户说的内容与最近的技能不相关 → 按正常规则判断为新意图

                ## 决策规则（严格遵守）

                1. **技能匹配优先**：如果用户意图与以下任一技能匹配，必须返回：
                   {"action": "skill", "skill": "<技能名>", "params": {}, "reason": "<中文理由>"}

                2. 当用户输入中包含"拓户"、"潜客"、"开户客户清单"、"推荐客户"、"客户详情"、"客户清单"、"上传客户清单"、"上传清单"、"导入客户"、"上传Excel"、"导入清单"、"上传客户"等关键词时，必须匹配为 recommend_corporate_customers 技能。

                3. 当用户输入中包含"查询"、"风险"、"开户风险"、"风险预查"等关键词时，必须匹配为 check_company_risk 技能。

                4. 如果是普通聊天、开户咨询、或其他非技能类对话，返回：
                   {"action": "chat", "reason": "<中文理由>"}

                5. 当用户输入中包含"推荐产品"、"产品推荐"、"产品智荐"、"适合什么产品"、"产品匹配"、"推荐金融产品"等关键词，且**未描述具体资金需求场景**时，必须匹配为 recommend_products 技能。

                6. 当用户输入中**描述了具体的资金需求场景**（如包含具体金额、期限、用途等描述），必须匹配为 match_products_intelligently 技能。

                7. 当用户输入中包含"办理开户"、"协助开户"、"同意开户"、"开始开户"、"开户资料"、"准备开户"等关键词时，必须匹配为 open_corporate_account 技能。

                8. 当用户输入中包含"历史尽调"、"查询历史"、"尽调记录"、"历史报告"、"查一下之前"、"以往的尽调"、"历史查询"、"查看历史"、"尽调历史"、"以前的报告"等关键词时，必须匹配为 query_due_diligence_reports 技能。

                ## 可用技能

                """ + skillsPrompt + """

                ## 重要规则

                - **只输出 JSON，不要输出任何其他文本**
                - 不要包裹在 ```json 代码块中
                - reason 字段必须用中文简述理由
                - 提取 company_name 时不要包含"查询"、"是否存在开户风险"、"的"等模板词语""";
    }
}