package com.IDDagent.service;

import com.IDDagent.config.AppConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import com.IDDagent.model.Message;

import java.util.*;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final WebClient webClient;
    private final AppConfig config;

    public AgentService(WebClient webClient, AppConfig config) {
        this.webClient = webClient;
        this.config = config;
    }

    public String getSystemPrompt() {
        // ... 保持不变 ...
        return """
                你是一位中国工商银行智能尽调助手，专门辅助客户经理和网点柜员完成对公客户的尽职调查（KYC/CDD）全流程工作。你依托工商银行金融科技体系，综合运用大数据、人工智能等技术手段，为一线业务人员提供信息核实、风险识别、资料分析与报告生成、查询历史尽调记录等智能化辅助服务。
                                           ## 你的能力范围
                                           1. 信息智能核实：辅助客户经理高效完成客户身份信息、经营资质、受益所有人等核心信息的采集、核验与交叉比对。
                                           2. 风险识别与评级：识别重大风险事项，并按照风险程度形成风险分级。
                                           3. 尽调报告撰写：根据选择的模板以及提交的材料，生成尽调报告。
                                           4. 历史尽调查询：根据企业名称查询历史尽调记录。
                                           5. 信息缺口识别：主动指出材料缺失、数据矛盾、无法验证事项和需要进一步核实的问题。
                                           ## 交互规范
                                           1. 始终保持专业、耐心、友好的态度
                                           2. 使用清晰的结构化回复，适当使用标题和列表
                                           3. 当用户询问具体操作时，给出步骤化的指导
                                           4. 主动提醒用户重要的注意事项
                                           5. 如果用户的问题超出你的知识范围，诚实说明并建议人工查询
                                           6. 每次对话开始时，主动问候并询问客户需要什么帮助
                                           7. 在收集信息时，一次只问1-2个问题，避免信息过载
                                           8. 使用 Markdown 格式让回复更加清晰易读""";
    }

    /**
     * 流式聊天（含历史上下文）
     * @param userMessage 当前用户消息
     * @param history 历史消息列表（不含当前消息）
     */
    public Flux<String> streamChat(String userMessage, List<Message> history) {
        String apiKey = config.getDeepseek().getApiKey();
        String baseUrl = config.getDeepseek().getBaseUrl();
        String model = config.getModel().getName();

        if (apiKey == null || apiKey.isEmpty()) {
            log.error("DEEPSEEK_API_KEY not configured");
            return Flux.error(new RuntimeException("DEEPSEEK_API_KEY not configured"));
        }

        // 构建消息列表：system + 历史消息 + 当前用户消息
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", getSystemPrompt()));

        // 追加历史消息（限制最近 20 条，避免 token 溢出）
        if (history != null && !history.isEmpty()) {
            List<Message> recentHistory = history.size() > 20
                    ? history.subList(history.size() - 20, history.size())
                    : history;
            for (Message msg : recentHistory) {
                String content = msg.getContent();
                // 如果是技能返回的 JSON（过长），用简短摘要替代
                if ("assistant".equals(msg.getRole()) && content != null && content.startsWith("{")) {
                    continue; // 跳过 JSON 格式的技能结果，避免污染对话上下文
                }
                if (content != null && content.length() > 1000) {
                    content = content.substring(0, 1000) + "...(已截断)";
                }
                messages.add(Map.of("role", msg.getRole(), "content", content != null ? content : ""));
            }
        }

        // 追加当前用户消息
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("stream", true);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 2000);

        log.info("Sending stream request to DeepSeek, model: {}, history messages: {}", model, history != null ? history.size() : 0);

        return webClient.post()
                .uri(baseUrl + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)   // 关键：必须设置接受流式响应
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(line -> log.debug("Raw SSE line: {}", line))  // 调试日志
                .mapNotNull(this::extractDelta)
                .doOnNext(chunk -> log.debug("Extracted chunk: {}", chunk))
                .doOnComplete(() -> log.info("Stream completed"))
                .doOnError(e -> log.error("Stream error", e))
                .onErrorResume(e -> {
                    log.error("Stream error, returning fallback message", e);
                    return Flux.just("抱歉，服务暂时不可用，我暂时无法回答，请稍后再试。");
                });
    }

    /**
     * 从 SSE 行中提取 content 增量
     */
    @SuppressWarnings("unchecked")
    private String extractDelta(String line) {
        if (line == null || line.isBlank()) return null;

        // 处理可能的空行或 [DONE]
        if (line.trim().isEmpty()) return null;
        if ("data: [DONE]".equals(line.trim())) return null;

        // 标准 SSE: "data: {...}"
        if (line.startsWith("data: ")) {
            String json = line.substring(6).trim();
            if (json.isEmpty() || "[DONE]".equals(json)) return null;
            try {
                Map<String, Object> data = mapper.readValue(json, new TypeReference<>() {});
                List<Map<String, Object>> choices = (List<Map<String, Object>>) data.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    // 流式响应使用 delta，非流式可能用 message，这里兼容两者
                    Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                    if (delta == null) {
                        // 非流式 fallback（仅用于调试，但 stream=true 时应为 delta）
                        delta = (Map<String, Object>) choice.get("message");
                    }
                    if (delta != null) {
                        Object content = delta.get("content");
                        if (content instanceof String s && !s.isEmpty()) {
                            return s;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse SSE data: {}", json, e);
            }
        } else {
            // 非标准格式（可能直接是 JSON 块），尝试直接解析
            try {
                Map<String, Object> data = mapper.readValue(line, new TypeReference<>() {});
                List<Map<String, Object>> choices = (List<Map<String, Object>>) data.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                    if (delta != null) {
                        Object content = delta.get("content");
                        if (content instanceof String s && !s.isEmpty()) {
                            return s;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 忽略非 JSON 行
            }
        }
        return null;
    }
}