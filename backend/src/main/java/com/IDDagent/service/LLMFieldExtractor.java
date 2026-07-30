package com.IDDagent.service;

import com.IDDagent.config.AppConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 字段提取服务 —— 利用大模型的语义理解能力，
 * 从附件原始文本中提取结构化财务数据字段。
 */
@Service
public class LLMFieldExtractor {

    private static final Logger log = LoggerFactory.getLogger(LLMFieldExtractor.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RAW_TEXT_LENGTH = 30000; // 单次调用最大文本长度

    private final WebClient webClient;
    private final AppConfig config;

    public LLMFieldExtractor(WebClient webClient, AppConfig config) {
        this.webClient = webClient;
        this.config = config;
    }

    /**
     * 使用 LLM 从附件原始文本中提取结构化字段
     *
     * @param rawText     上传文件的原始文本内容
     * @param templateId  模板 ID，用于确定需要提取的字段
     * @param companyName 企业名称（基本背景信息）
     * @param creditCode  统一信用代码
     * @return 提取到的字段键值对
     */
    public Map<String, String> extractFields(String rawText, String templateId,
                                              String companyName, String creditCode) {
        String apiKey = config.getDeepseek().getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DEEPSEEK_API_KEY not set, LLM field extraction skipped");
            return Map.of();
        }

        // 截断过长的文本
        if (rawText != null && rawText.length() > MAX_RAW_TEXT_LENGTH) {
            log.warn("原始文本过长 ({} 字符)，截断至 {} 字符", rawText.length(), MAX_RAW_TEXT_LENGTH);
            rawText = rawText.substring(0, MAX_RAW_TEXT_LENGTH);
        }

        // 构建让 LLM 解析的 prompt
        String prompt = buildPrompt(rawText, templateId, companyName, creditCode);

        try {
            // 构建请求体
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", config.getModel().getName());
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content",
                            "你是一个专业的财务报表分析助手。你的任务是从企业财务报表或财务数据文本中，"
                            + "提取出所有要求的财务指标字段。请严格按 JSON 格式输出，只输出一个 JSON 对象，不要包含任何其他文字。"
                            + "数值保留原始格式（如 8526.30、8,526.30 都可以）。"
                            + "如果某个字段在文本中找不到，输出空字符串 \"\"。"),
                    Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 2000);
            requestBody.put("thinking", Map.of("type", "disabled"));

            // 同步调用 LLM（在非 reactive 服务中 block 是安全的）
            String response = webClient.post()
                    .uri(config.getDeepseek().getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class).flatMap(body -> {
                                log.error("LLM API 返回错误: status={}, body={}", resp.statusCode(), body);
                                return Mono.error(new RuntimeException("LLM API error: " + resp.statusCode()));
                            }))
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(60));

            if (response == null || response.isEmpty()) {
                log.warn("LLM 返回空响应");
                return Map.of();
            }

            // 解析响应
            return parseLlmResponse(response);

        } catch (Exception e) {
            log.error("LLM 字段提取失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 构建提示词 */
    private String buildPrompt(String rawText, String templateId,
                                String companyName, String creditCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从上传的财务报表文件中提取的原始文本内容，请根据这些数据提取相关财务指标。\n\n");
        sb.append("企业名称：").append(companyName).append("\n");
        if (creditCode != null && !creditCode.isEmpty()) {
            sb.append("统一信用代码：").append(creditCode).append("\n");
        }
        sb.append("\n===== 文件原始文本 =====\n");
        sb.append(rawText);
        sb.append("\n===== 文本结束 =====\n\n");

        sb.append("请从以上文本中提取以下字段的值，输出 JSON 对象（key 为字段名，value 为提取到的值，数值不要加单位）：\n\n");

        // 按模板类型输出不同的字段列表
        if ("financial_analysis".equals(templateId)) {
            sb.append("1. 财务指标（万元）：\n");
            sb.append("   - 营业收入2024, 营业收入2023, 营业收入2022\n");
            sb.append("   - 营业成本2024, 营业成本2023, 营业成本2022\n");
            sb.append("   - 净利润2024, 净利润2023, 净利润2022\n");
            sb.append("   - 总资产2024, 总资产2023, 总资产2022\n");
            sb.append("   - 资产负债率 (百分比数字如 58.5)\n\n");
            sb.append("2. 资产负债明细表（万元）：\n");
            sb.append("   - 货币资金202212, 货币资金202312, 货币资金202412, 货币资金202509\n");
            sb.append("   - 应收账款202212, 应收账款202312, 应收账款202412, 应收账款202509\n");
            sb.append("   - 预付账款202212, 预付账款202312, 预付账款202412, 预付账款202509\n");
            sb.append("   - 其他应收款202212, 其他应收款202312, 其他应收款202412, 其他应收款202509\n\n");
            sb.append("3. 营收预测（万元）：\n");
            sb.append("   - 营业收入预测2025, 营业成本预测2025, 销售利润预测2025\n\n");
            sb.append("4. 文本描述：\n");
            sb.append("   - 营收来源描述: 营业收入的主要来源是什么\n");
            sb.append("   - 利润为负原因: 如果利润为负，原因是什么（否则空字符串）\n");
            sb.append("   - 是否覆盖本息: \"能\" 或 \"不能\"\n");
            sb.append("   - 固定收入组成: 企业固定收入主要由哪些部分组成\n");
            sb.append("   - 审计机构: 审计报告出具方\n");
            sb.append("   - 主营业务: 企业的主营业务\n");
            sb.append("   - 员工人数: 企业员工数量\n");
        } else if ("due_diligence_brief".equals(templateId)) {
            sb.append("1. 企业信息：\n");
            sb.append("   - 主营业务, 员工人数\n\n");
            sb.append("2. 财务指标（万元）：\n");
            sb.append("   - 营业收入2024, 净利润2024, 总资产2024\n");
            sb.append("   - 资产负债率 (百分比数字)\n\n");
            sb.append("3. 其他：\n");
            sb.append("   - 前五大客户占比, 关联交易额（万元）, 审计机构\n");
        } else if ("credit_evaluation".equals(templateId)) {
            sb.append("1. 财务指标（万元）：\n");
            sb.append("   - 营业收入2024, 净利润2024, 总资产2024\n\n");
            sb.append("2. 评分（满分100）：\n");
            sb.append("   - 偿债能力评分, 盈利能力评分, 经营能力评分, 发展能力评分, 担保能力评分, 综合评分\n\n");
            sb.append("3. 授信信息：\n");
            sb.append("   - 信用等级, 建议授信额度（万元）, 授信期限, 担保方式\n\n");
            sb.append("4. 文本：\n");
            sb.append("   - 行业前景: 该行业的发展前景描述\n");
        }

        sb.append("\n请只输出 JSON 对象，例如：\n");
        sb.append("{\"营业收入2024\": \"8526.30\", \"净利润2024\": \"1250.00\", ...}\n");
        sb.append("如果某个字段在文本中找不到，值设为空字符串 \"\"。");

        return sb.toString();
    }

    /** 解析 LLM 返回的 JSON 响应 */
    private Map<String, String> parseLlmResponse(String response) {
        try {
            // 提取 choices[0].message.content
            Map<String, Object> respMap = mapper.readValue(response, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");

            if (choices == null || choices.isEmpty()) {
                log.warn("LLM 响应无 choices");
                return Map.of();
            }

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.getOrDefault("content", "");

            if (content.isEmpty()) {
                log.warn("LLM 返回内容为空");
                return Map.of();
            }

            // 找到 JSON 内容（可能被 ```json ... ``` 包裹）
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = content.substring(start, end + 1);
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = mapper.readValue(json, LinkedHashMap.class);
                Map<String, String> result = new LinkedHashMap<>();
                fields.forEach((k, v) -> {
                    if (v != null) {
                        result.put(k, v.toString());
                    }
                });
                log.info("LLM 字段提取成功: {} 个字段", result.size());
                return result;
            }

            log.warn("LLM 返回内容中未找到 JSON: {}", content.substring(0, Math.min(100, content.length())));
            return Map.of();

        } catch (Exception e) {
            log.error("LLM 响应解析失败: {}", e.getMessage());
            return Map.of();
        }
    }
}
