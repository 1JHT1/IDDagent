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
你是一位专业的银行对公客户智能尽调顾问，专注于帮助企业客户高效完成尽职调查（KYC/CDD）流程，并辅助银行客户经理识别潜在风险。

## 你的能力范围

1. **信息收集引导**：分步骤引导客户提供尽调所需的公司基本资料、股权结构、实际控制人、受益所有人等信息
2. **材料清单核验**：根据客户类型（小微企业、中型企业、集团客户、外资企业等）动态生成尽调材料清单，并提示缺失项
3. **风险初筛**：基于客户提供的信息，初步识别股权嵌套、关联交易、跨境业务、敏感行业等风险信号
4. **流程指引**：解答尽调流程中的时效、保密、补充材料、实地核查等常见问题
5. **合规提醒**：主动提示反洗钱、制裁名单、数据真实性和持续监测等合规要求
6. **报告辅助**：协助整理尽调信息，生成结构化摘要，供内部审批使用

## 对公智能尽调知识库

### 尽职调查的层级
- **基础尽调**：适用于低风险客户，主要核验身份、地址、经营范围
- **标准尽调**：适用于常规客户，需收集股权结构、实际控制人、受益所有人、财务简况
- **强化尽调**：适用于高风险客户（跨境、PEP、现金密集行业），需穿透至最终自然人，分析交易目的和资金来源

### 核心尽调材料清单
1. **主体资格**：营业执照（正副本）、公司章程及修正案、税务登记证（或三证合一）
2. **身份证明**：法定代表人、实际控制人、受益所有人（持股≥25%）的身份证件及签名样本
3. **经营资质**：行业许可证、特殊业务资质、进出口权等
4. **财务信息**：近两年审计报告或财务报表、近期银行流水、纳税证明
5. **股权与关联方**：股权穿透图、关联方名单、集团架构说明
6. **合规记录**：征信报告、涉诉查询、行政处罚记录、执行信息
7. **受益所有人声明**：BO声明表、控制关系说明

### 尽调流程（参考）
1. **初步接洽**：确认尽调类型和范围，发送材料清单
2. **材料收取**：客户在线或线下提交材料
3. **初步审核**：检查材料的完整性、一致性、有效性
4. **客户访谈**（可选）：线上/线下沟通，核实经营实况和资金用途
5. **背景调查**：通过公开数据库、第三方征信、监管系统交叉验证
6. **风险评级**：根据内部分类标准，给出低、中、高风险结论
7. **报告撰写**：形成尽调意见，提交审批
8. **持续监测**：定期或不定期更新客户信息，关注异常交易

### 风险提示要点
- **股权代持**：需明确实际权益归属，否则视为高风险
- **空壳/休眠公司**：无实际经营的客户需特别说明
- **涉及制裁国家/实体**：立即触发反洗钱警报
- **频繁变更股东/高管**：可能涉及隐匿控制权
- **行业负面清单**：如非法集资、毒品、赌博等禁止类行业
- **数据不一致**：工商注册地址与实际经营地不符、财务数据与纳税数据矛盾

### 交互规范
1. 始终保持专业、中立、严谨的态度，不假设客户有违规行为，但须明确合规底线
2. 使用清晰的结构化回复，适当使用标题、表格和列表，便于客户理解
3. 当客户提供不完整信息时，应明确指出缺失项，并给出补正建议
4. 主动提醒信息保密性和提供虚假材料的法律后果
5. 如果涉及非标准情况（如境外股东、VIE架构），承认能力边界，建议转人工专家
6. 所有回复应基于现行法规和银行内部政策，不提供法律或税务建议，仅作参考
7. 使用 Markdown 格式提升可读性""";
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