package com.IDDagent.service;

import com.IDDagent.config.AppConfig;
import com.IDDagent.model.Message;
import com.IDDagent.skill.IntentMatcher;
import com.IDDagent.skill.IntentMatcher.SkillCandidate;
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

@Service
public class CoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final double CLARIFY_CONFIDENCE_THRESHOLD = 0.6;
    /** 级联升级阈值：light 模型 skill 置信度低于此值，升级主模型完整规则兜底重判 */
    private static final double ESCALATE_CONFIDENCE_THRESHOLD = 0.6;

    private final SkillRegistry skillRegistry;
    private final IntentMatcher intentMatcher;
    private final WebClient webClient;
    private final AppConfig config;
    private final ContextMemoryService contextMemoryService;

    public CoordinatorService(SkillRegistry skillRegistry, IntentMatcher intentMatcher,
                              WebClient webClient, AppConfig config,
                              ContextMemoryService contextMemoryService) {
        this.skillRegistry = skillRegistry;
        this.intentMatcher = intentMatcher;
        this.webClient = webClient;
        this.config = config;
        this.contextMemoryService = contextMemoryService;
    }

    /**
     * 三层路由入口（非阻塞响应式）
     * ① IntentMatcher 确定性前置匹配 → ② LLM 仲裁 / ③ LLM 完整规则兜底
     */
    public Mono<Map<String, Object>> routeIntent(String userMessage, List<Message> history,
                                                  String conversationId) {
        String apiKey = config.getDeepseek().getApiKey();
        String baseUrl = config.getDeepseek().getBaseUrl();
        String model = config.getModel().getCoordinator();

        // API key 缺失：先尝试本地模糊兜底，未命中才返回 chat fallback（同步快速）
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DEEPSEEK_API_KEY not set, trying fuzzy match then chat");
            Map<String, Object> local = skillRegistry.matchByKeyword(userMessage);
            if (local != null) {
                injectUserInput(local, userMessage);
                return Mono.just(local);
            }
            return Mono.just(fallbackMap("API key not configured"));
        }

        // ① 确定性前置匹配
        List<SkillCandidate> candidates = intentMatcher.match(userMessage);
        log.info("IntentMatcher candidates for '{}': {}", userMessage, candidates);

        if (candidates.isEmpty()) {
            // ③ 无候选 → LLM 完整规则兜底（含多意图检测）
            return callLLM(buildFullRulePrompt(userMessage, history), userMessage, history);
        }

        // 冲突仲裁
        List<SkillCandidate> resolved = intentMatcher.resolveConflict(candidates);
        log.info("After resolveConflict: {}", resolved);

        if (resolved.size() == 1) {
            // 唯一候选 → 优先直接提取参数（跳过 LLM，零延迟）
            SkillCandidate c = resolved.get(0);
            Map<String, Object> directParams = tryDirectParamExtraction(c, userMessage, conversationId);
            if (directParams != null) {
                log.info("Direct param extraction succeeded for skill '{}': {}", c.skillName(), directParams);
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("action", "skill");
                decision.put("skill", c.skillName());
                decision.put("params", directParams);
                decision.put("confidence", 0.95);
                decision.put("reason", "IntentMatcher 确定性匹配 + 直接参数提取");
                return Mono.just(decision);
            }
            // 直接提取失败 → 回退到 LLM 提取参数
            log.info("Direct param extraction failed for skill '{}', falling back to LLM", c.skillName());
            return callLLM(buildParamExtractionPrompt(c, userMessage, history), userMessage, history);
        }

        // ② 多候选 → LLM 仲裁（含多意图检测）
        return callLLM(buildArbitrationPrompt(resolved, userMessage, history), userMessage, history);
    }

    /**
     * 调用 LLM 并解析响应（模型级联：先便宜模型，低置信度/复杂场景升级主模型）。
     * 第一层 light 模型按给定 prompt（参数提取/仲裁/完整规则）决策；
     * 需要升级时第二层主模型改用完整规则 prompt 兜底重判，结果直接返回不再递归。
     */
    private Mono<Map<String, Object>> callLLM(String systemPrompt, String userMessage, List<Message> history) {
        return callModel(config.getModel().getLight(), systemPrompt, userMessage, history)
                .flatMap(decision -> {
                    if (shouldEscalate(decision)) {
                        log.info("Light model insufficient (action={}, confidence={}, reason={}), escalating to main model",
                                decision.get("action"), decision.get("confidence"), decision.get("reason"));
                        return callModel(config.getModel().getCoordinator(),
                                buildFullRulePrompt(userMessage, history), userMessage, history);
                    }
                    return Mono.just(decision);
                });
    }

    /**
     * 实际调用指定模型并解析响应
     */
    private Mono<Map<String, Object>> callModel(String model, String systemPrompt, String userMessage, List<Message> history) {
        String baseUrl = config.getDeepseek().getBaseUrl();
        String apiKey = config.getDeepseek().getApiKey();

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 追加最近的历史消息（限最近 8 条）
        if (history != null && !history.isEmpty()) {
            List<Message> recentHistory = history.size() > 8
                    ? history.subList(history.size() - 8, history.size())
                    : history;
            for (Message msg : recentHistory) {
                String content = msg.getContent();
                if (content == null || content.isEmpty()) continue;
                if (content.startsWith("{")) {
                    content = "[系统返回了结构化卡片结果]";
                }
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...(截断)";
                }
                messages.add(Map.of("role", msg.getRole(), "content", content));
            }
        }

        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("thinking", Map.of("type", "disabled"));
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 600);

        return webClient.post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseResponse)          // 解析响应（可能返回 null）
                .map(decision -> {                 // 解析失败 → 本地模糊兜底；技能决策注入用户原始输入
                    if (decision == null) {
                        Map<String, Object> local = skillRegistry.matchByKeyword(userMessage);
                        return local != null ? local : fallbackMap("未提取到有效意图，模糊匹配未命中");
                    }
                    String action = (String) decision.getOrDefault("action", "");
                    if ("skill".equals(action) || "multi_skill".equals(action)) {
                        injectUserInput(decision, userMessage);
                    }
                    return decision;
                })
                .onErrorResume(e -> {              // 错误时降级：先模糊兜底，未命中才 chat
                    log.error("Coordinator LLM call failed: {}", e.getMessage());
                    Map<String, Object> local = skillRegistry.matchByKeyword(userMessage);
                    if (local != null) {
                        injectUserInput(local, userMessage);
                        return Mono.just(local);
                    }
                    return Mono.just(fallbackMap("意图识别请求失败"));
                });
    }

    /**
     * 级联升级判定：light 模型决策是否需要主模型兜底重判。
     * - skill 置信度低于阈值 → 升级（便宜模型拿不准）
     * - clarify / multi → 升级（拿不准或多意图属于复杂场景，主模型完整规则更可靠）
     * - chat → 仅解析失败兜底（degraded）才升级；模型明确判定闲聊时不浪费主模型
     * - 未知 action / 异常 → 升级
     */
    static boolean shouldEscalate(Map<String, Object> decision) {
        if (decision == null) return true;
        String action = (String) decision.get("action");
        if (action == null) return true;
        switch (action) {
            case "skill":
                return getConfidence(decision) < ESCALATE_CONFIDENCE_THRESHOLD;
            case "clarify":
            case "multi":
                return true;
            case "chat":
                return Boolean.TRUE.equals(decision.get("degraded"));
            default:
                return true;
        }
    }

    /**
     * 解析 DeepSeek 返回的文本，提取决策 JSON
     * 支持四种 action：skill / chat / clarify / multi
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
                            log.warn("Coordinator returned unknown skill '{}', falling back to fuzzy/chat", skillName);
                            return null;
                        }
                    }
                    log.info("Coordinator intent: {}, reason: {}", decision.get("action"), decision.getOrDefault("reason", "unknown"));
                    return decision;
                }
                // 多意图：校验 intents，全部无效返回 null（走模糊兜底），部分有效返回过滤后的
                if ("multi_skill".equals(action)) {
                    return parseMultiSkill(decision);
                }
                // 兼容：LLM 直接将技能名作为 action（如 {"action":"verify_business_license"}）
                if (skillRegistry.get(action) != null) {
                    decision.put("action", "skill");
                    decision.put("skill", action);
                    log.info("Coerced action from '{}' to skill", action);
                    return decision;
                }

                if ("skill".equals(action)) {
                    String skillName = (String) decision.getOrDefault("skill", "");
                    if (skillRegistry.get(skillName) == null) {
                        log.warn("LLM returned unknown skill '{}', falling back to chat", skillName);
                        return fallbackMap("意图识别返回未知技能", true);
                    }
                    // 置信度检查
                    double confidence = getConfidence(decision);
                    if (confidence > 0 && confidence < CLARIFY_CONFIDENCE_THRESHOLD) {
                        log.info("Low confidence {} for skill '{}', converting to clarify", confidence, skillName);
                        return buildClarifyDecision(decision);
                    }
                    log.info("Coordinator intent: skill={}, confidence={}, reason: {}",
                            skillName, confidence, decision.getOrDefault("reason", "unknown"));
                    return decision;
                }

                if ("chat".equals(action)) {
                    log.info("Coordinator intent: chat, reason: {}", decision.getOrDefault("reason", "unknown"));
                    return decision;
                }

                if ("clarify".equals(action)) {
                    log.info("Coordinator intent: clarify, reason: {}", decision.getOrDefault("reason", "unknown"));
                    return decision;
                }

                if ("multi".equals(action)) {
                    log.info("Coordinator intent: multi, reason: {}", decision.getOrDefault("reason", "unknown"));
                    return decision;
                }
            }

            log.warn("No valid decision JSON found in response: {}", text);
            return null;

        } catch (Exception e) {
            log.warn("JSON parse error: {}", e.getMessage());
            return null;
        }
    }

    private static double getConfidence(Map<String, Object> decision) {
        Object conf = decision.get("confidence");
        if (conf instanceof Number) return ((Number) conf).doubleValue();
        if (conf instanceof String) {
            try { return Double.parseDouble((String) conf); } catch (Exception e) { return 1.0; }
        }
        return 1.0; // 无 confidence 字段视为高置信度
    }

    /**
     * 解析 multi_skill 决策：校验 intents 非空、逐个校验技能已注册、去重同技能、截断前 3 个。
     * 全部无效返回 null（交模糊兜底），部分有效返回过滤后的决策。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMultiSkill(Map<String, Object> decision) {
        Object raw = decision.get("intents");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            log.warn("multi_skill: intents missing or empty, defer to fuzzy/chat");
            return null;
        }
        List<Map<String, Object>> intents = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean chatSeen = false;
        for (Object item : list) {
            if (intents.size() >= 3) break;
            Map<String, Object> intent;
            if (item instanceof String s) {
                // LLM 可能输出字符串数组形式（如 ["verify_business_license","generate_report"]）
                // 而不是规范的对象数组，逐项转换为 intent 结构再走统一校验
                intent = new LinkedHashMap<>();
                intent.put("skill", s);
                intent.put("params", new LinkedHashMap<>());
                intent.put("priority", intents.size() + 1);
            } else if (item instanceof Map<?, ?> map) {
                intent = new LinkedHashMap<>((Map<String, Object>) map);
            } else {
                continue;
            }
            String skill = String.valueOf(intent.get("skill"));
            if (skill.isEmpty()) continue;
            if ("chat".equals(skill) || skillRegistry.get(skill) == null) {
                // 非技能问答意图（或 LLM 误输出的未知技能）→ 统一转为 chat 意图，保留用户问题不丢失；最多保留一个
                if (chatSeen) {
                    log.warn("multi_skill: extra chat intent removed");
                    continue;
                }
                if (!"chat".equals(skill)) {
                    log.warn("multi_skill: unknown skill '{}' converted to chat intent", skill);
                }
                chatSeen = true;
                intent.put("skill", "chat");
            } else {
                // 按 (skill, company_name) 去重：同一技能针对不同企业需全部保留，
                // 供意图冲突澄清检测（detectAndResolve 第 1 级：同技能多主体）；
                // 同技能同企业仅保留一个，避免重复执行。
                String dedupKey = skill;
                Object p = intent.get("params");
                if (p instanceof Map<?, ?> pm) {
                    Object c = pm.get("company_name");
                    if (c != null && !String.valueOf(c).isBlank()) {
                        dedupKey = skill + "|" + c;
                    }
                }
                if (!seen.add(dedupKey)) {
                    log.warn("multi_skill: duplicate intent '{}' removed", dedupKey);
                    continue;
                }
            }
            intents.add(intent);
        }
        if (intents.isEmpty()) {
            log.warn("multi_skill: all intents invalid, defer to fuzzy/chat");
            return null;
        }
        decision.put("intents", intents);
        log.info("Coordinator multi-skill intent: {} intents, reason: {}",
                intents.size(), decision.getOrDefault("reason", "unknown"));
        return decision;
    }

    /**
     * 向技能决策 params 注入用户原始输入（与 pending 路径 ChatController 注入的 _user_input 保持一致）。
     * 使技能层无论经 Coordinator 路由还是 pending 直连，都能以 _user_input 为唯一可信输入源，
     * 从而识别用户本次是否明确提供了企业标识，避免上下文自动补全参数被误当作本次输入。
     * multi_skill 决策时对每个 intent 递归注入。
     */
    @SuppressWarnings("unchecked")
    private void injectUserInput(Map<String, Object> decision, String userMessage) {
        if ("multi_skill".equals(decision.get("action"))) {
            Object raw = decision.get("intents");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> intent) {
                        injectUserInput((Map<String, Object>) intent, userMessage);
                    }
                }
            }
            return;
        }
        Object rawParams = decision.get("params");
        Map<String, Object> params;
        if (rawParams instanceof Map) {
            params = (Map<String, Object>) rawParams;
        } else {
            params = new LinkedHashMap<>();
            decision.put("params", params);
        }
        // 备选技能
        List<String> alternatives = (List<String>) decision.getOrDefault("alternatives", List.of());
        for (String alt : alternatives) {
            if (skillRegistry.get(alt) != null) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("skill", alt);
                c.put("label", skillRegistry.getSkillLabel(alt));
                c.put("description", skillRegistry.get(alt).getDescription());
                candidates.add(c);
            }
        }
        clarify.put("candidates", candidates);
        clarify.put("message", "您的问题可能有多种理解，请选择您想要的操作：");
        return clarify;
    }

    /**
     * 解析失败兜底：action=chat + degraded 标记。
     * degraded=true 表示模型有输出但解析不出有效决策（light 能力不足，可升级主模型）；
     * degraded=false 表示调用失败（升级无意义，直接兜底）。
     */
    private Map<String, Object> fallbackMap(String reason, boolean degraded) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("action", "chat");
        fallback.put("reason", reason);
        fallback.put("degraded", degraded);
        return fallback;
    }

    // ============================================================
    // 直接参数提取（不依赖 LLM）
    // ============================================================

    /**
     * 尝试直接从用户消息中提取技能参数（跳过 LLM 调用）。
     * 成功返回参数 Map；失败返回 null（调用方应回退到 LLM）。
     */
    private Map<String, Object> tryDirectParamExtraction(SkillCandidate candidate,
                                                         String userMessage,
                                                         String conversationId) {
        Map<String, Object> params = new LinkedHashMap<>();

        // 1. 尝试提取统一信用代码（优先"统一信用代码:XXX"字段，其次 18 位裸代码）
        String creditCode = CompanyNameExtractor.extractCreditCode(userMessage);
        if (!creditCode.isEmpty()) {
            params.put("credit_code", creditCode);
        }

        // 2. 尝试从消息中提取企业名称（统一公共清洗链）
        String companyName = CompanyNameExtractor.extractCompanyName(
                userMessage, null, null, candidate.matchedKeywords());

        if (companyName != null && !companyName.isEmpty()) {
            params.put("company_name", companyName);
        }

        // 3. 如果消息中未提取到企业名，检查上下文记忆
        if (!params.containsKey("company_name") && !params.containsKey("credit_code")) {
            ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
            if (!ctx.isEmpty()) {
                // 消息中包含上下文引用词（如"这家""该公司"），说明用户指的是上一轮的企业
                boolean hasContextRef = ContextMemoryService.isContextReference(userMessage);
                if (hasContextRef) {
                    if (ctx.creditCode != null && !ctx.creditCode.isEmpty()) {
                        params.put("credit_code", ctx.creditCode);
                    }
                    if (ctx.companyName != null && !ctx.companyName.isEmpty()) {
                        params.put("company_name", ctx.companyName);
                    }
                    log.info("Context memory fill: creditCode={}, companyName={}",
                            ctx.creditCode, ctx.companyName);
                } else {
                    // 无法从消息提取，也无上下文引用 → 直接提取失败
                    return null;
                }
            } else {
                // 无上下文记忆 → 直接提取失败
                return null;
            }
        }

        return params.isEmpty() ? null : params;
    }

    // ============================================================
    // Prompt 构建
    // ============================================================

    /**
     * 唯一候选 → LLM 仅提取参数
     */
    private String buildParamExtractionPrompt(SkillCandidate candidate, String userMessage, List<Message> history) {
        String skillsPrompt = skillRegistry.getSkillsPrompt();
        return """
                你是一个参数提取器。用户意图已确定为技能「%s」（%s）。
                请从用户输入和对话历史中提取该技能所需的参数。

                ## 上下文记忆
                系统维护了当前会话的上下文记忆（最近操作的企业主体）。即使用户没有在当前消息中明确提及企业名称，你仍然应该从历史中提取。

                ## 可用技能描述
                %s

                ## 输出格式
                只输出 JSON，不要输出其他文本：
                {"action": "skill", "skill": "%s", "params": {...}, "confidence": 0.95, "reason": "<中文理由>"}

                如果无法提取任何参数，params 为空对象 {}。
                不要包裹在 ```json 代码块中。
                """.formatted(candidate.skillName(), candidate.label(), skillsPrompt, candidate.skillName());
    }

    /**
     * 多候选 → LLM 仲裁（含多意图检测）
     */
    private String buildArbitrationPrompt(List<SkillCandidate> candidates, String userMessage, List<Message> history) {
        String skillsPrompt = skillRegistry.getSkillsPrompt();
        StringBuilder candidateDesc = new StringBuilder();
        for (SkillCandidate c : candidates) {
            candidateDesc.append("- ").append(c.skillName()).append("（").append(c.label()).append("）: ")
                    .append("触发词命中: ").append(c.matchedKeywords()).append("\n");
        }

        return """
                你是一个任务规划主控智能体。用户的输入可能匹配多个技能，请判断用户真实意图。

                ## 候选技能
                %s

                ## 上下文记忆
                系统维护了当前会话的上下文记忆（最近操作的企业主体）。

                ## 对话历史
                以下是最近对话历史，请结合理解用户意图。

                ## 决策规则
                1. 如果用户意图明确指向某一个候选技能 → 输出 {"action":"skill","skill":"<技能名>","params":{...},"confidence":0.8,"reason":"<中文理由>"}
                2. 如果用户同时表达了多个互不排斥的意图 → 输出 {"action":"multi","skills":[{"skill":"<技能名1>","params":{...}},{"skill":"<技能名2>","params":{...}}],"reason":"<中文理由>"}
                3. 如果无法确定用户意图 → 输出 {"action":"clarify","candidates":[{"skill":"<技能名>","label":"<中文标签>","description":"<描述>"}],"message":"您的问题可能有多种理解，请选择您想要的操作：","reason":"<中文理由>"}
                4. 如果明显是普通聊天 → 输出 {"action":"chat","reason":"<中文理由>"}

                ## 可用技能
                %s

                ## 重要规则
                - 只输出 JSON，不要输出其他文本
                - 不要包裹在 ```json 代码块中
                - reason 字段必须用中文
                - confidence 范围 0~1，表示你对判断的信心
                - 提取 company_name 时不要包含"查询"、"的"等模板词语
                """.formatted(candidateDesc, skillsPrompt);
    }

    /**
     * 无候选 → LLM 完整规则兜底（含多意图检测）
     */
    private String buildFullRulePrompt(String userMessage, List<Message> history) {
        String skillsPrompt = skillRegistry.getSkillsPrompt();
        return """
                你是一个任务规划主控智能体。分析用户输入（含对话历史上下文），判断意图并做出路由决策。

                ## 上下文记忆
                系统维护了当前会话的上下文记忆（最近操作的企业主体）。即使用户没有在当前消息中明确提及企业名称，只要意图明确（如「查下风险」），你仍然应该路由到对应的技能。系统会自动从上下文记忆中补充缺失的企业参数。

                ## 对话历史
                以下是当前会话的最近对话历史。请结合历史消息理解用户意图：
                - 如果用户说"换一家"、"再看另一家"、"查另一家"等 → 表示想切换企业，应匹配到最近使用的同类型技能
                - 如果用户说"再查2024年的"、"换个时间"等 → 表示想变更查询条件，应匹配到最近使用的同类型技能
                - 如果用户说的内容与最近的技能不相关 → 按正常规则判断为新意图

                ## 决策规则（严格遵守）

                1. **除非意图明确匹配，否则一律 chat**：**只有**当用户输入中的关键词明确且唯一地指向某个技能时，才路由到该技能。如果意图模糊、不确定、或仅包含企业名称/人名/简短词语，则一律返回 chat。
                   - 路由到技能时返回格式：{"action": "skill", "skill": "<技能名>", "params": {}, "confidence": 0.9, "reason": "<中文理由>"}
                   - 路由到聊天时返回格式：{"action": "chat", "reason": "<中文理由>"}

                2. 当用户输入中包含"风险"、"风险识别"、"企业风险"、"风险预查"等关键词时，必须匹配为 check_company_risk 技能。路由到 check_company_risk 时，仅当用户输入**明确包含企业名称**时才提取填入 params.company_name（如"查小米的风险"→{"company_name":"小米"}）；输入含 18 位统一信用代码时填入 params.credit_code；**用户未提及任何企业名称/信用代码时（如纯功能句"风险识别"、多意图"信息核实和风险识别"），params 必须留空，严禁猜测填充或把功能句残渣当作企业名**，由技能追问主体。

                3. 如果是普通聊天、意图不明确、仅含公司名或其他非技能类对话，一律返回：
                   {"action": "chat", "reason": "<中文理由>"}
                   不要将{"action": "chat"}写成其他格式。

                4. 当用户输入中包含"生成报告"、"报告生产"、"报告生成"、"尽调报告"、"财务分析报告"、"授信评估"、"报告模板"、"生成尽调"、"智能尽调"、"上传资料生成报告"等关键词时，必须匹配为 generate_report 技能。

                5. generate_report 技能的多轮交互参数提取：
                   a. 当用户选择了模板（消息中包含"选择"+"模板"、"使用"+"模板"或"(ID:"），从模板名称或ID中提取 template_id。
                   b. 当用户触发生成（消息中包含"为"+"生成"），提取 template_id、company_name，并设置 action="generate"
                   c. 如果消息中包含"附件文件ID:"，提取逗号分隔的文件ID列表填充到 attachment_file_ids 参数
                   d. 如果消息中包含"统一信用代码:"，提取信用代码填充到 credit_code 参数

                6. 当用户输入中包含"核实信息"、"信息核实"、"信息核查"、"营业执照核实"、"信息核验"、"营业执照核验"等关键词时，必须匹配为 verify_business_license 技能。路由到 verify_business_license 时，将用户输入中的企业名称提取填入 params.company_name（如"核实小米"→{"company_name":"小米"}）；输入含 18 位统一信用代码时填入 params.credit_code；用户未提及任何企业名称/信用代码时（如纯功能句"信息核实"）params 必须留空，严禁猜测填充或把功能句残渣当作企业名，由技能追问主体。

                7. **多轮对话路由**：结合**上一轮交互语境**：
                    a. 如果上一轮助手消息是技能结果，且当前用户输入简短，则路由到**上一轮相同的技能**
                    b. 如果上轮路由为 chat，则按正常规则判断本轮意图
                    c. 如果用户明确表达了新的意图，按新意图路由

                8. 当用户输入中包含"历史尽调"、"查询历史"、"尽调记录"、"历史报告"、"查一下之前"、"以往的尽调"、"历史查询"、"查看历史"、"尽调历史"、"以前的报告"等关键词时，必须匹配为 query_due_diligence_reports 技能。当用户输入表达"查询某企业的（尽调）报告"（如"查小米科技的报告"、"查一下小米科技的尽调报告"、"看看XX的报告"），即包含查询行为词（查、查询、查一下、查看、看看、找、找一下、搜索）且涉及"报告"、"尽调报告"、"尽调"时，也必须匹配为 query_due_diligence_reports 技能；此场景下"尽调报告"归属查询（本规则），不归属生成（规则4）。路由到 query_due_diligence_reports 时，仅当用户输入**明确包含企业名称**时才提取填入 params.company_name（如"查小米科技的报告"→{"company_name":"小米科技"}）；输入含 18 位统一信用代码时填入 params.credit_code；**用户未提及任何企业名称/信用代码时（如纯功能句"查询历史尽调报告"、多意图"查询历史尽调报告和生成报告"），params 必须留空，严禁猜测填充或把功能句残渣当作企业名**，由技能追问主体。

                9. 当用户输入中包含"股东"、"股权结构"、"股权分布"等关键词时，必须匹配为 query_shareholder_info 技能。

                10. 当用户输入中包含"受益人"、"实际控制人"、"受益所有人"等关键词时，必须匹配为 query_beneficiary_info 技能。

                11. 当用户输入中包含"企业族谱"、"家族图谱"、"关联企业图谱"等关键词时，必须匹配为 query_company_genealogy 技能。

                12. 当用户输入中包含"海关认证"、"海关高级认证"、"AEO认证"等关键词时，必须匹配为 query_customs_auth 技能；当用户输入中包含"海关失信"、"海关黑名单"、"海关失信名单"等关键词时，必须匹配为 query_customs_blacklist 技能。

                13. 当用户输入中包含"冻结"、"司法冻结"、"账户冻结"等关键词，且同时包含"账户"、"账号"等关键词时，必须匹配为 query_account_freeze_tag 技能。

                14. 当用户输入中包含"授信"、"授信额度"、"综合授信"、"授信余额"等关键词时，必须匹配为 query_credit_granting 技能。

                15. 当用户输入中包含"人行账管"、"人民银行账户管理"、"账户管控"、"央行账户管理"等关键词时，必须匹配为 query_pboc_account_control 技能。

                16. 当用户输入中包含"查询"、"查一下"、"查查"、"提供"、"获取"、"看一下"、"看看"、"了解一下"等查询行为词，且对话对象为法人企业，且**不包含**规则2~15中的任何技能关键词时，必须匹配为 query_company_basic_info 技能。

                17. **附件未说明用途必须返回 chat**：当用户消息仅包含附件信息且**不包含**"核实"、"核验"、"核查"、"报告"、"生成"等明确用途关键词时，一律返回 chat。

                18. **多意图识别**：当用户输入中包含多个互不排斥的意图（如"核实信息并做风险识别"），输出：
                    {"action": "multi", "skills": [{"skill": "<技能名1>", "params": {...}}, {"skill": "<技能名2>", "params": {...}}], "reason": "<中文理由>"}

                18. **多意图识别**：当用户在同一条消息中同时表达**多个意图**（如"查下小米的风险，顺便看下股权结构"、"查下小米的风险，顺便告诉我今天天气怎么样"）时，输出 multi_skill 决策：
                {"action":"multi_skill","intents":[{"skill":"check_company_risk","params":{"company_name":"小米"},"priority":1},{"skill":"query_shareholder_info","params":{"company_name":"小米"},"priority":2}],"reason":"用户同时表达了风险查询和股权查询两个意图"}
                - 意图类型：技能意图（skill 从"可用技能"列表中选择，params 为该技能所需参数）与普通问答意图（skill 固定为 "chat"，params 中 question 字段存放用户的原问题，如 {"skill":"chat","params":{"question":"今天天气怎么样"},"priority":2}）可混合出现
                - intents 数组：单次最多 3 个意图；priority 从 1 开始，越小越先执行
                - 同一技能针对不同企业时分别输出多个 intent（保留各自 company_name，如"查下小米的风险，再查下海康威视的风险"应输出两个 check_company_risk，company_name 分别为小米/海康威视）；同技能同企业只输出一个
                - 只有意图明确时才使用 multi_skill；普通多主题聊天仍返回 chat
                - 若用户意图虽多但高度模糊、无法确定具体技能，仍返回 chat

                ## 可用技能

                """ + skillsPrompt + """

                ## 重要规则

                - **只输出 JSON，不要输出任何其他文本**
                - 不要包裹在 ```json 代码块中
                - reason 字段必须用中文简述理由
                - 提取 company_name 时不要包含"查询"、"的"等模板词语
                - confidence 范围 0~1，表示你对判断的信心""";
    }
}
