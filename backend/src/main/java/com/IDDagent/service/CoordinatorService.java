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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * 路由意图（非阻塞响应式）
     * @param userMessage 用户输入
     * @param history 当前会话历史消息（不含当前消息），用于多轮语境理解
     * @return Mono<Map<String, Object>> 决策结果
     */
    public Mono<Map<String, Object>> routeIntent(String userMessage, List<Message> history) {
        String systemPrompt = buildSystemPrompt();
        String apiKey = config.getDeepseek().getApiKey();
        String baseUrl = config.getDeepseek().getBaseUrl();
        String model = config.getModel().getCoordinator();

        // API key 缺失，直接返回 fallback（同步快速）
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DEEPSEEK_API_KEY not set, defaulting to chat");
            return Mono.just(fallbackMap("API key not configured"));
        }

        // 构建消息列表：system + 对话历史 + 当前用户消息
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 追加最近的历史消息（限最近 8 条，避免 token 溢出）
        if (history != null && !history.isEmpty()) {
            List<Message> recentHistory = history.size() > 8
                    ? history.subList(history.size() - 8, history.size())
                    : history;
            for (Message msg : recentHistory) {
                String content = msg.getContent();
                if (content == null || content.isEmpty()) continue;
                // 技能返回的 JSON 过大，用简短摘要替代
                if (content.startsWith("{")) {
                    content = "[系统返回了结构化卡片结果]";
                }
                // 限制单条消息长度
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...(截断)";
                }
                messages.add(Map.of("role", msg.getRole(), "content", content));
            }
        }

        // 追加当前用户消息
        messages.add(Map.of("role", "user", "content", userMessage));

        // 构建请求体
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        // 关闭思考模式，确保 temperature 生效且输出可预测
        requestBody.put("thinking", Map.of("type", "disabled"));
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 300);

        // 打印调试信息（非阻塞）
        try {
            System.out.println("===== 完整请求 URL: " + baseUrl + "/chat/completions");
            System.out.println("===== 请求体 JSON: " + mapper.writeValueAsString(requestBody));
            System.out.println("===== Authorization 头: Bearer " + apiKey.substring(0, 10) + "...");
        } catch (Exception e) {
            // 忽略打印异常
        }

        // 发起非阻塞调用，并处理结果
        return webClient.post()
                .uri(baseUrl + "/chat/completions")
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
                // 兼容：LLM 直接将技能名作为 action（如 {"action":"verify_business_license"}）
                if (skillRegistry.get(action) != null) {
                    decision.put("action", "skill");
                    decision.put("skill", action);
                    log.info("Coerced action from '{}' to skill", action);
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
                系统维护了当前会话的上下文记忆（最近操作的企业主体）。即使用户没有在当前消息中明确提及企业名称，只要意图明确（如「查下风险」），你仍然应该路由到对应的技能。系统会自动从上下文记忆中补充缺失的企业参数。

                ## 对话历史
                以下是当前会话的最近对话历史（user/assistant 消息）。请结合历史消息理解用户意图：
                - 如果用户说"换一家"、"再看另一家"、"查另一家"等 → 表示想切换企业，应匹配到最近使用的同类型技能
                - 如果用户说"再查2024年的"、"换个时间"等 → 表示想变更查询条件，应匹配到最近使用的同类型技能
                - 如果用户说的内容与最近的技能不相关 → 按正常规则判断为新意图

                ## 决策规则（严格遵守）

                1. **除非意图明确匹配，否则一律 chat**：**只有**当用户输入中的关键词明确且唯一地指向某个技能时，才路由到该技能。如果意图模糊、不确定、或仅包含企业名称/人名/简短词语，则一律返回 chat。
                   - 路由到技能时返回格式：{"action": "skill", "skill": "<技能名>", "params": {}, "reason": "<中文理由>"}
                   - 路由到聊天时返回格式：{"action": "chat", "reason": "<中文理由>"}

                2. 当用户输入中包含"风险"、"风险识别"、"企业风险"、"风险预查"等关键词时，必须匹配为 check_company_risk 技能。

                3. 如果是普通聊天、意图不明确、仅含公司名或其他非技能类对话，一律返回：
                   {"action": "chat", "reason": "<中文理由>"}
                   不要将{"action": "chat"}写成其他格式。

                4. 当用户输入中包含"生成报告"、"尽调报告"、"财务分析报告"、"授信评估"、"报告模板"、"生成尽调"、"智能尽调"、"上传资料生成报告"等关键词时，必须匹配为 generate_report 技能。

                5. generate_report 技能的多轮交互参数提取：
                   a. 当用户选择了模板（消息中包含"选择"+"模板"、"使用"+"模板"或"(ID:"），从模板名称或ID中提取 template_id。如果消息中有"(ID:xxx)"格式，则 xxx 即为 template_id，无需从模板名称映射。如果只有模板名称没有ID，则从名称映射到ID（financial_analysis=借款人财务分析报告, due_diligence_brief=尽调简报, credit_evaluation=授信评估报告, business_license_analysis=营业执照信息解析）
                   b. 当用户触发生成（消息中包含"为"+"生成"），提取 template_id（同规则a）、company_name（"为"和"生成"之间的企业名称），并设置 action="generate"
                   c. 如果消息中包含"附件文件ID:"，提取逗号分隔的文件ID列表填充到 attachment_file_ids 参数（字符串数组）
                   d. 如果消息中包含"统一信用代码:"，提取紧跟在后面的信用代码（到")"或末尾），填充到 credit_code 参数

                6. 当用户输入中包含"核实信息"、"信息核实"、"信息核查"、"营业执照核实"、"信息核验"、"营业执照核验"等关键词时，必须匹配为 verify_business_license 技能。

                7. **多轮对话路由**：你收到的消息包含完整对话历史。判断意图时必须结合**上一轮交互语境**：
                    a. 如果上一轮助手消息是技能结果（标注为[系统返回了结构化卡片结果]），且当前用户输入简短（如仅为企业名称、确认词等），则应该路由到**上一轮相同的技能**，并将用户输入作为参数补充。例如：上轮路由到 verify_business_license 并返回了卡片询问企业名称，本轮用户输入"小米公司"，应继续路由到 verify_business_license，传递 company_name="小米公司"。
                    b. 如果上轮路由为 chat（普通对话），则按正常规则判断本轮意图。
                    c. 如果用户明确表达了新的意图（无论上轮是什么），按新意图路由。

                8. 当用户输入中包含"历史尽调"、"查询历史"、"尽调记录"、"历史报告"、"查一下之前"、"以往的尽调"、"历史查询"、"查看历史"、"尽调历史"、"以前的报告"等关键词时，必须匹配为 query_due_diligence_reports 技能。

                9. 当用户输入中包含"股东"、"股权结构"等关键词时，必须匹配为 query_shareholder_info 技能。

                10. 当用户输入中包含"受益人"、"实际控制人"、"受益所有人"等关键词时，必须匹配为 query_beneficiary_info 技能。

                11. 当用户输入中包含"企业族谱"、"家族图谱"、"关联企业图谱"等关键词时，必须匹配为 query_company_genealogy 技能。

                12. 当用户输入中包含"海关认证"、"海关高级认证"、"AEO认证"等关键词时，必须匹配为 query_customs_auth 技能；当用户输入中包含"海关失信"、"海关黑名单"、"海关失信名单"等关键词时，必须匹配为 query_customs_blacklist 技能。

                13. 当用户输入中包含"冻结"、"司法冻结"、"账户冻结"等关键词，且同时包含"账户"、"账号"等关键词时，必须匹配为 query_account_freeze_tag 技能。

                14. 当用户输入中包含"授信"、"授信额度"、"综合授信"、"授信余额"等关键词时，必须匹配为 query_credit_granting 技能。

                15. 当用户输入中包含"人行账管"、"人民银行账户管理"、"账户管控"、"央行账户管理"等关键词时，必须匹配为 query_pboc_account_control 技能。

                16. 当用户输入中包含"查询"、"查一下"、"提供"、"获取"、"看一下"等查询行为词，且对话对象为法人企业，且**不包含**规则2~15中的任何技能关键词（风险、股东、受益人、族谱、海关、冻结、授信、账管等）时，必须匹配为 query_company_basic_info 技能（企业基本信息查询作为查询类兜底）。

                ## 可用技能

                """ + skillsPrompt + """

                ## 重要规则

                - **只输出 JSON，不要输出任何其他文本**
                - 不要包裹在 ```json 代码块中
                - reason 字段必须用中文简述理由
                - 提取 company_name 时不要包含"查询"、"的"等模板词语""";
    }
}