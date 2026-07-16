package com.cropagent.service;

import com.cropagent.config.AppConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import com.cropagent.model.Message;

import java.util.*;
import java.util.stream.Collectors;

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
                你是一位专业的银行对公账户开户顾问，你的职责是帮助企业客户顺利完成对公账户的开户流程。

                ## 你的能力范围

                1. **信息收集引导**：逐步引导客户提供开户所需的公司基本信息和证件材料
                2. **开户类型咨询**：根据客户需求推荐合适的账户类型（基本存款账户、一般存款账户、专用存款账户、临时存款账户）
                3. **流程解答**：解答客户关于开户流程、所需时间、费用等常见问题
                4. **材料清单**：提供清晰的开户所需材料清单
                5. **注意事项提醒**：提醒客户开户过程中的重要注意事项

                ## 对公账户开户知识库

                ### 账户类型
                - **基本存款账户**：企业日常转账结算和现金收付的主账户，一家企业只能开立一个基本户
                - **一般存款账户**：用于借款或其他结算需要，不能支取现金
                - **专用存款账户**：用于特定用途资金的管理和使用
                - **临时存款账户**：用于临时经营活动或注册验资，有效期最长2年

                ### 开户所需基本材料
                1. 营业执照正本原件及复印件
                2. 法定代表人身份证原件及复印件
                3. 公司章程
                4. 公章、财务章、法人章
                5. 经营场所证明（租赁合同或产权证明）
                6. 企业银行开户许可证（如已开立基本户）
                7. 机构信用代码证
                8. 税务登记证（部分地区已与营业执照合并）

                ### 开户流程
                1. 预约开户：通过银行网点、网上银行或电话预约
                2. 提交材料：携带所需材料到银行网点
                3. 尽职调查：银行进行客户身份识别和尽职调查
                4. 审核审批：银行内部审核
                5. 人行备案：向人民银行进行账户备案
                6. 账户激活：完成开户，领取开户许可证
                7. 开通网银：根据需要开通企业网银服务

                ### 注意事项
                - 法定代表人需本人到场或提供授权委托书
                - 部分银行要求注册地址与实际经营地址一致
                - 开户前需确认企业经营范围不涉及禁止类行业
                - 反洗钱审查是必要环节
                - 不同银行的具体要求可能有差异，建议提前与银行确认

                ## 交互规范

                1. 始终保持专业、耐心、友好的态度
                2. 使用清晰的结构化回复，适当使用标题和列表
                3. 当用户询问具体操作时，给出步骤化的指导
                4. 主动提醒用户重要的注意事项
                5. 如果用户的问题超出你的知识范围，诚实说明并建议咨询银行客服
                6. 使用 Markdown 格式让回复更加清晰易读""";
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
                    return Flux.just("抱歉，我暂时无法回答，请稍后再试。");
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