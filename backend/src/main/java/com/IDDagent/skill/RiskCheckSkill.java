package com.IDDagent.skill;

import com.IDDagent.config.AppConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

@Component
public class RiskCheckSkill {

    private static final Logger log = LoggerFactory.getLogger(RiskCheckSkill.class);

    private static final String RISK_FILE = "data-template/risk_check.json";
    private static final String NAME_INDEX_FILE = "data-template/company_name_index.json";
    private static final int MIN_AUTO_MATCH_SCORE = 80;
    private static final int MAX_SUGGESTIONS = 3;
    /** _user_input 清洗用技能动词/查询后缀（供 CompanyNameExtractor 统一清洗链） */
    private static final String RISK_VERBS = "风险预查|风险筛查|风险识别|预查|筛查|查询|查一下|查|看看";
    private static final String RISK_SUFFIXES = "的风险情况|的风险信息|的风险|风险情况|风险信息|风险|情况|信息";

    /**
     * 疑问/问题句式判定：含典型疑问词（什么/怎么/是否/有没有/吗/呢 等）或提问类名词
     * （介绍/定义/含义/意思/包含/包括）。用户以问题句式表达时（如"风险识别是什么"、
     * "风险识别包括哪些内容"）是在提问而非提供企业名——兜底提取必须放弃把清理后残渣
     * 当主体，否则会把问题文本直接当公司名去查询/误报"未找到企业"，应先询问主体。
     * 真实企业名（含"风险"字样的"XX风险投资"）不含疑问词，不受影响。
     * 与 IntentPlannerService/InformationCheckSkill/HistoricalDDQuerySkill 的 QUESTION_PATTERN 同源。
     */
    private static final java.util.regex.Pattern QUESTION_PATTERN = java.util.regex.Pattern.compile(
            "什么|哪些|哪个|怎么|如何|为什么|为啥|多少|有没有|是否|是不是|嘛|呢|吗|啥|干嘛|干什么|做什么|介绍|定义|含义|意思|包含|包括");

    private static final ObjectMapper mapper = new ObjectMapper();

    private final SkillRegistry registry;
    private final WebClient webClient;
    private final AppConfig config;

    public RiskCheckSkill(SkillRegistry registry, WebClient webClient, AppConfig config) {
        this.registry = registry;
        this.webClient = webClient;
        this.config = config;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "check_company_risk",
                "当用户进行风险预查、企业风险筛查、" +
                        "查询xx企业风险报告、风险预检时调用此技能。" +
                        "根据企业统一信用代码或企业名称查询风险信息，返回风险结论和详细风险报告链接。",
                this::handle,
                Map.of(
                        "credit_code", new Skill.SkillParam("string", "企业统一信用代码，18位数字+字母", false, "91110108MA01B3XK2P"),
                        "company_name", new Skill.SkillParam("string", "企业名称，用于模糊匹配", false, "北京星河科技有限公司")
                )
        ), List.of("风险", "风控", "风评", "不良记录", "风险预查", "风险提示", "有没有风险"));
    }

    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();

        // 从 _user_input 兜底提取企业标识（与 InformationCheckSkill 兜底一致）：
        // pending 路径（info_needed 后用户补充企业名，如回复"小米"）完全跳过 LLM，复用第一轮
        // 的空 params 只注入 _user_input，企业名仅存在于原始输入中——若不兜底提取，会再次误报
        // "请提供企业名称或统一信用代码进行查询"而死循环。Coordinator 路由路径同样注入 _user_input。
        String userInput = ((String) params.getOrDefault("_user_input", "")).trim();

        // 多意图规划标记（buildPlan 注入）：多意图句子（如"风险识别和信息核实"）中 LLM 可能把
        // 功能词残渣（"风险识别"）填进 company_name，兜底提取也可能把多意图句子的残余
        // （"和信息核实"）当公司名——都不是用户提供的企业主体。标记生效时：
        // 有主体 → 先做功能词残渣清洗（清洗后为空视为未提供）；无主体 → 跳过 _user_input
        // 兜底提取（多意图句子本身不是企业名），直接询问主体。
        boolean fromMultiIntent = Boolean.TRUE.equals(params.get("_from_multi_intent"));

        // 共享广播主体标记（broadcastParams 写入）：主体来自用户对"请提供企业名称"询问的显式回答
        // 或技能解析（info_needed 响应），由规划层广播到后续缺失主体的步骤。广播主体虽不在该步骤
        // 自己的 _user_input 中（仍是 buildPlan 注入的原始多意图句子），但可信度等同用户输入——
        // 主体可信度校验与多意图功能词清洗都必须跳过它，否则会被清空再次询问主体，形成多意图
        // 流程中"回答后一直循环"。
        boolean broadcastSubject = Boolean.TRUE.equals(params.get("_broadcast_subject"));

        // 主体可信度校验（前置到 _user_input 兜底提取之前）：company_name 若非空又非用户本次
        // 输入提供的（ctx 记忆预补全/LLM 猜测/规划继承占位 _inherited_subject 等填的非用户指定值），
        // 一律先清空。必须在校验通过后再从用户真实输入提取——否则占位主体非空会挡住下方兜底提取
        // （提取条件要求 companyName 为空），清空后占位已丢失、无从再提取，只能反复询问
        // "请提供企业名称或统一信用代码进行查询"而死循环。先清占位、再从用户真实输入提取，形成自救链路。
        // 用户输入中含该名称（用户直接提供）时保留，正常走解析。credit_code 不做该校验：
        // 18 位代码只可能来自用户输入或上一轮解析结果（buildPlan/handleSingleSkill 已跳过 ctx 预补全）。
        if (creditCode.isEmpty() && !companyName.isEmpty() && !userInput.contains(companyName) && !broadcastSubject) {
            log.info("RiskCheckSkill 主体非用户本次提供，清空后从 _user_input 重新提取: '{}' (input='{}')",
                    companyName, userInput);
            companyName = "";
        }

        // 多意图主体清洗：LLM 把多意图句子的功能词残渣填进 company_name（如"风险识别"、
        // "风险识别和信息核实"中的"风险识别"）时，先按与 _user_input 兜底一致的词表清洗；
        // 清洗后为空或仍为功能词残渣（如"和信息核实"）→ 视为未提供主体，后续直接询问。
        if (fromMultiIntent && creditCode.isEmpty() && !companyName.isEmpty() && !broadcastSubject) {
            String cleaned = companyName
                    .replaceAll("风险预查|风险预检|风险筛查|风险查询|风险识别|风险报告|风险提示|有没有风险|风险", "")
                    .replaceAll("(?:帮我|请|麻烦|要|想|一下|的|关于|查|查询|看看|看下)", "")
                    .replaceAll("[，。；、！？!?\\s：:（）()]+", "")
                    .trim();
            if (cleaned.isEmpty() || isFunctionalResidue(cleaned)) {
                log.info("RiskCheckSkill 多意图主体清洗为功能残渣，清空以询问主体: '{}' (input='{}')",
                        companyName, userInput);
                companyName = "";
            } else if (!cleaned.equals(companyName)) {
                log.info("RiskCheckSkill 清洗多意图主体功能词残渣: '{}' → '{}'", companyName, cleaned);
                companyName = cleaned;
            }
        }

        // 多意图无主体时跳过 _user_input 兜底提取：多意图句子本身是功能描述而非企业名，
        // 提取必然得到残余（如"风险识别和信息核实" → "和信息核实"），应询问主体而非查询
        if (!fromMultiIntent && creditCode.isEmpty() && companyName.isEmpty() && !userInput.isEmpty()) {
            // 1) 18 位统一信用代码
            java.util.regex.Matcher ccMatcher = java.util.regex.Pattern
                    .compile("[0-9A-Z]{18}").matcher(userInput.toUpperCase());
            if (ccMatcher.find()) {
                creditCode = ccMatcher.group();
            } else {
                // 2) 通用兜底：移除风险行为词/功能词后剩余内容视为企业名（如 pending 第二轮直接回复"小米"）
                String cleaned = userInput
                        .replaceAll("风险预查|风险预检|风险筛查|风险查询|风险识别|风险报告|风险提示|有没有风险|风险", "")
                        .replaceAll("(?:帮我|请|麻烦|要|想|一下|的|关于|查|查询|看看|看下)", "")
                        .replaceAll("[，。；、！？!?\\s：:（）()]+", "")
                        .trim();
                // 最终防线：清理后若仅剩"查/风险/一下"等纯功能残渣（如"查下风险""风险识别"），视为无企业名
                if (cleaned.matches("(?:一下|一遍|下|遍|帮我|请|麻烦|要|想|的|关于|查|查询|风险)+")) cleaned = "";
                // 问题句式防护：清理后残余若仍含疑问词（"什么是风险识别"→"什么是"、"风险识别包括哪些内容"
                // →"包括哪些内容"），说明用户在提问而非提供企业名，放弃提取 → 下方询问主体
                if (!cleaned.isEmpty() && QUESTION_PATTERN.matcher(cleaned).find()) {
                    log.info("RiskCheckSkill 清理结果疑似问题句式，放弃提取: '{}' (input='{}')",
                            cleaned, userInput);
                    cleaned = "";
                }
                if (cleaned.length() >= 2) {
                    companyName = cleaned;
                    log.info("RiskCheckSkill 从 _user_input 兜底提取企业名称: '{}' (input='{}')",
                            companyName, userInput);
                }
            }
        }

        Map<String, Object> riskData = DataLoader.loadJson(RISK_FILE);

        if (!creditCode.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) riskData.get(creditCode);
            if (result != null) {
                return buildResult(result);
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            resp.put(Skill.KEY_STEP_DONE, true);
            resp.put("message", "未查询到统一信用代码为 " + creditCode + " 的企业风险信息，请核实代码是否正确。");
            return resp;
        }

        if (!companyName.isEmpty()) {
            Map<String, String> nameIndex = loadNameIndex();
            Map<String, Object> resolved = resolveCompanyMatch(companyName, nameIndex);

            if (resolved.containsKey("credit_code_without_action")) {
                return handle(userId, Map.of("credit_code", resolved.get("credit_code_without_action")));
            }

            if (resolved.containsKey("credit_code")) {
                // Handled internally by resolveCompanyMatch
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) riskData.get(resolved.get("credit_code"));
                if (result != null) {
                    return buildResult(result);
                }
                // 名称匹配成功但 risk_check.json 中无该企业风险数据（如索引中的混淆/无数据企业）：
                // 区分"未收录"与"名称有误"，避免把存在企业误报为"未找到"
                Map<String, Object> resp = new HashMap<>();
                resp.put("action", "not_found");
                resp.put(Skill.KEY_STEP_DONE, true);
                resp.put("message", "未找到与「" + companyName + "」匹配的企业，请确认企业名称是否正确。" +
                        "可尝试使用更简短的关键词，或提供统一信用代码查询。");
                return resp;
            }

            return resolved;
        }

        // 用户未提供任何企业标识（未给企业名/信用代码，兜底提取也失败）：询问主体而非报"未找到"
        // （与信息核实/历史报告查询一致：查询/核实/风险识别的主体必须由用户显式提供）
        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "info_needed");
        // 未到达步骤结束点：等待用户补充企业标识后重跑当前步骤
        resp.put(Skill.KEY_STEP_DONE, false);
        resp.put("message", "请提供企业名称或统一信用代码进行查询。");
        return resp;
    }

    /**
     * 判定提取结果/清洗结果是否为"功能句残渣"而非真实企业名：
     * - 纯功能词组合（"风险识别""信息核实"），如多意图句子中 LLM 把"风险识别"填进 company_name；
     * - 多意图功能句残渣：如"风险识别和信息核实"兜底提取到的"和信息核实"，剥掉首尾连接词后
     *   仍为功能词组合（"信息核实"）；
     * - 问题句残渣：含疑问词（"是什么""包括哪些内容"）。
     * 真实企业名（含"风险"字样的"XX风险投资"）含词表外的字（投资/企业名主体），不受影响。
     * 与 InformationCheckSkill.isFunctionalResidue 同源（词表保持一致）。
     */
    private static boolean isFunctionalResidue(String s) {
        if (s == null || s.isEmpty()) return true;
        String stripped = s.replaceFirst("^(?:和|并|及|或|与|以及)+", "")
                .replaceFirst("(?:和|并|及|或|与|以及)+$", "");
        if (stripped.isEmpty()) return true;
        // 问题句式（"是什么""包括哪些内容"）是提问而非企业名
        if (QUESTION_PATTERN.matcher(stripped).find()) return true;
        return stripped.matches("(?:信息|核实|核验|核查|验证|执照|营业执照|风险|风控|风评|尽调|报告|查询|识别|融资|贷款|授信|调查|评估|历史|检索|搜索|生成|制作|创建|一下|一遍|下|遍)+");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildResult(Map<String, Object> data) {
        String baseUrl = DataLoader.buildBaseUrl();
        String code = (String) data.get("credit_code");
        // 模板数据用 enterprise_name，统一映射为 company_name
        String companyName = (String) data.getOrDefault("company_name", data.get("enterprise_name"));
        String riskLevel = computeRiskLevel(data);
        boolean hasRisk = !"low".equals(riskLevel);

        Map<String, Object> result = new HashMap<>();
        result.put("action", "result");
        // 到达步骤结束点：风险核查结果已返回，步骤完成
        result.put(Skill.KEY_STEP_DONE, true);
        result.put("credit_code", code);
        result.put("company_name", companyName);
        result.put("has_risk", hasRisk);
        result.put("risk_level", riskLevel);
        // 风险摘要由大模型根据报告 details 内容摘要生成，替代模板固定文案（调用失败时回退模板原文）
        result.put("risk_summary", summarizeRiskSummary(data));
        result.put("h5_url", baseUrl + "/h5/risk-report.html?code=" + code);
        return result;
    }

    /**
     * 利用大模型对风险报告 details 内容进行摘要总结，生成风险预查结论文案。
     * - 输入：各维度（工商信息/反洗钱/融安E信）的结构化核查项
     * - 输出：2-3 句客观精炼的风险摘要
     * - 失败回退：未配置 API Key 或调用/解析异常时回退模板原始 risk_summary，保证卡片始终有内容
     */
    private String summarizeRiskSummary(Map<String, Object> data) {
        String apiKey = config.getDeepseek().getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DEEPSEEK_API_KEY not set, risk summary falls back to raw text");
            return (String) data.getOrDefault("risk_summary", "暂未发现风险点");
        }
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", config.getModel().getName());
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content",
                            "你是一位银行风控合规专家。根据客户风险核查的结构化结果，客观、精炼地总结该客户的风险状况：" +
                            "说明整体风险等级、主要风险点（命中项）及其严重程度。直接输出 2-3 句总结文本，" +
                            "不要输出 JSON、标题或任何多余格式。"),
                    Map.of("role", "user", "content", buildSummaryPrompt(data))
            ));
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 500);
            requestBody.put("thinking", Map.of("type", "disabled"));

            String response = webClient.post()
                    .uri(config.getDeepseek().getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class).flatMap(body -> {
                                log.error("风险摘要 LLM 调用失败: status={}, body={}", resp.statusCode(), body);
                                return Mono.error(new RuntimeException("LLM API error: " + resp.statusCode()));
                            }))
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            String summary = parseSummaryResponse(response);
            if (summary != null && !summary.isBlank()) {
                log.info("风险摘要生成成功: {}", summary);
                return summary;
            }
        } catch (Exception e) {
            log.error("风险摘要生成失败，回退模板原文: {}", e.getMessage());
        }
        return (String) data.getOrDefault("risk_summary", "暂未发现风险点");
    }

    /**
     * 将报告 details 各维度核查项结构化为供 LLM 摘要的文本输入。
     */
    @SuppressWarnings("unchecked")
    private String buildSummaryPrompt(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("企业名称：").append(data.getOrDefault("company_name", data.get("enterprise_name"))).append("\n");
        sb.append("统一信用代码：").append(data.getOrDefault("credit_code", "")).append("\n");
        sb.append("以下为该客户各维度风险核查结果：\n");
        Object detailsObj = data.get("details");
        if (detailsObj instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) detailsObj;
            for (var entry : details.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> module = (Map<String, Object>) entry.getValue();
                sb.append("\n【").append(module.getOrDefault("name", entry.getKey())).append("】\n");
                Object itemsObj = module.get("items");
                if (!(itemsObj instanceof List)) continue;
                for (Object itemObj : (List<?>) itemsObj) {
                    if (!(itemObj instanceof Map)) continue;
                    Map<String, Object> item = (Map<String, Object>) itemObj;
                    Object result = item.getOrDefault("result", item.getOrDefault("riskLevel", "—"));
                    sb.append("- ").append(item.getOrDefault("name", "")).append("：").append(result);
                    Object detail = item.get("detail");
                    if (detail != null && !detail.toString().isBlank()) {
                        sb.append("（").append(detail).append("）");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 解析 LLM 摘要响应，提取 choices[0].message.content 纯文本。
     */
    @SuppressWarnings("unchecked")
    private String parseSummaryResponse(String response) {
        if (response == null || response.isBlank()) return "";
        try {
            Map<String, Object> respMap = mapper.readValue(response, new TypeReference<>() {});
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            if (choices == null || choices.isEmpty()) return "";
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) return "";
            Object content = message.get("content");
            return content == null ? "" : content.toString().trim();
        } catch (Exception e) {
            log.warn("风险摘要响应解析失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 根据 details 中的各项指标计算风险等级。
     * - aml 中 has_risk=true 且 severity="high" 计为高风险项
     * - rongan 中 riskLevel="high" 计为高风险项
     * - aml 中 has_risk=true 且 severity="medium" 计为中风险项
     * - rongan 中 riskLevel="medium" 计为中风险项
     * - business_info 中 result="命中" 计为中风险项
     * 高风险项 >=1 → high；中风险项 >=2 → medium；否则 → low
     */
    @SuppressWarnings("unchecked")
    public static String computeRiskLevel(Map<String, Object> data) {
        Map<String, Object> details = (Map<String, Object>) data.get("details");
        if (details == null) return "low";

        int highCount = 0;
        int mediumCount = 0;

        // 反洗钱
        Map<String, Object> aml = (Map<String, Object>) details.get("aml");
        if (aml != null) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) aml.get("items");
            if (items != null) {
                for (Map<String, Object> item : items) {
                    Boolean hasRisk = (Boolean) item.get("has_risk");
                    if (Boolean.TRUE.equals(hasRisk)) {
                        String severity = (String) item.get("severity");
                        if ("high".equalsIgnoreCase(severity)) {
                            highCount++;
                        } else {
                            mediumCount++;
                        }
                    }
                }
            }
        }

        // 融安E信
        Map<String, Object> rongan = (Map<String, Object>) details.get("rongan");
        if (rongan != null) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) rongan.get("items");
            if (items != null) {
                for (Map<String, Object> item : items) {
                    String riskLevel = (String) item.get("riskLevel");
                    if (riskLevel == null) riskLevel = (String) item.get("risklevel");
                    if ("high".equalsIgnoreCase(riskLevel)) {
                        highCount++;
                    } else if ("medium".equalsIgnoreCase(riskLevel)) {
                        mediumCount++;
                    }
                }
            }
        }

        // 工商信息 — 只有特定指标的"命中"才算风险
        Map<String, Object> businessInfo = (Map<String, Object>) details.get("business_info");
        if (businessInfo != null) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) businessInfo.get("items");
            if (items != null) {
                for (Map<String, Object> item : items) {
                    String result = (String) item.get("result");
                    String name = (String) item.get("name");
                    if ("命中".equals(result) && name != null &&
                            (name.contains("一人多企") || name.contains("异常经营") || name.contains("空壳"))) {
                        mediumCount++;
                    }
                }
            }
        }

        if (highCount >= 1) return "high";
        if (mediumCount >= 2) return "medium";
        return "low";
    }

    private static final Map<String, String> RONGAN_STATUS_MAP = Map.of(
            "PENDING", "待处理",
            "MONITORING", "监控中",
            "RESOLVED", "已解决",
            "CLEAR", "正常"
    );

    /**
     * 将 data-template/risk_check.json 的原始数据标准化为 H5 页面所需格式。
     * - enterprise_name → company_name
     * - 计算 risk_level / has_risk
     * - rongan items: riskLevel/detectDate/status → result/has_risk/detail
     * - business_info items: 缺失 has_risk 时根据 result 补全
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeForH5(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>(raw);

        // 企业名称统一
        if (!result.containsKey("company_name") && result.containsKey("enterprise_name")) {
            result.put("company_name", result.get("enterprise_name"));
        }

        // 计算风险等级
        String riskLevel = computeRiskLevel(raw);
        result.put("risk_level", riskLevel);
        result.put("has_risk", !"low".equals(riskLevel));

        // 标准化 details 中各模块的 items
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        if (details != null) {
            for (var entry : details.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> module = (Map<String, Object>) entry.getValue();
                Object itemsObj = module.get("items");
                if (!(itemsObj instanceof List)) continue;
                List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                List<Map<String, Object>> normalizedItems = new ArrayList<>();
                for (Map<String, Object> item : items) {
                    Map<String, Object> ni = new LinkedHashMap<>(item);
                    // rongan 模块：将 riskLevel/detectDate/status 转为 result/has_risk/detail
                    if ("rongan".equals(entry.getKey())) {
                        String rl = (String) ni.getOrDefault("riskLevel", ni.get("risklevel"));
                        String status = (String) ni.get("status");
                        String detectDate = (String) ni.get("detectDate");
                        boolean hasRisk = "high".equalsIgnoreCase(rl) || "medium".equalsIgnoreCase(rl);
                        ni.put("result", hasRisk ? "命中" : "未命中");
                        ni.put("has_risk", hasRisk);
                        StringBuilder detail = new StringBuilder();
                        if (detectDate != null) detail.append("检测日期: ").append(detectDate);
                        if (status != null) {
                            if (detail.length() > 0) detail.append("  |  ");
                            detail.append("状态: ").append(RONGAN_STATUS_MAP.getOrDefault(status, status));
                        }
                        ni.put("detail", detail.toString());
                    }
                    // business_info 模块：补全缺失的 has_risk（仅特定指标的"命中"算风险）
                    if ("business_info".equals(entry.getKey())) {
                        if (!ni.containsKey("has_risk")) {
                            String r = (String) ni.get("result");
                            String nm = (String) ni.get("name");
                            ni.put("has_risk", "命中".equals(r) && nm != null &&
                                    (nm.contains("一人多企") || nm.contains("异常经营") || nm.contains("空壳")));
                        }
                    }
                    normalizedItems.add(ni);
                }
                module.put("items", normalizedItems);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadNameIndex() {
        Map<String, Object> data = DataLoader.loadJson(NAME_INDEX_FILE);
        return (Map<String, String>) (Map<?, ?>) data;
    }

    // Package-private for use by other skills
    static Map<String, Object> resolveCompanyMatch(String query, Map<String, String> nameIndex) {
        List<Map<String, Object>> matches = fuzzyMatchCompany(query, nameIndex);

        if (matches.isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            resp.put(Skill.KEY_STEP_DONE, true);
            resp.put("message", "未找到与「" + query + "」匹配的企业，请确认企业名称是否正确。" +
                    "可尝试使用更简短的关键词，或提供统一信用代码查询。");
            return resp;
        }

        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < Math.min(matches.size(), MAX_SUGGESTIONS); i++) {
            Map<String, Object> m = matches.get(i);
            Map<String, Object> opt = new HashMap<>();
            opt.put("credit_code", m.get("credit_code"));
            opt.put("company_name", m.get("company_name"));
            options.add(opt);
        }

        if (matches.size() == 1) {
            int score = ((Number) matches.get(0).get("_score")).intValue();
            if (score >= MIN_AUTO_MATCH_SCORE) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("credit_code", matches.get(0).get("credit_code"));
                return resp;
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            // 带候选列表：未到达步骤结束点，等待用户点击候选后重跑当前步骤
            resp.put(Skill.KEY_STEP_DONE, false);
            resp.put("keyword", query);
            resp.put("options", options);
            resp.put("message", "未找到与「" + query + "」完全匹配的企业，您是否要查询以下相似企业？");
            return resp;
        }

        int bestScore = ((Number) matches.get(0).get("_score")).intValue();
        int secondScore = matches.size() > 1 ? ((Number) matches.get(1).get("_score")).intValue() : 0;

        if (bestScore >= 95 && secondScore < MIN_AUTO_MATCH_SCORE) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("credit_code", matches.get(0).get("credit_code"));
            return resp;
        }

        if (bestScore >= MIN_AUTO_MATCH_SCORE) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "ambiguous");
            // 带候选列表：未到达步骤结束点，等待用户点击候选后重跑当前步骤
            resp.put(Skill.KEY_STEP_DONE, false);
            resp.put("keyword", query);
            resp.put("options", options);
            resp.put("message", "搜索到 " + matches.size() + " 家与「" + query + "」匹配的企业，请确认要查询哪一家：");
            return resp;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "not_found");
        // 带候选列表：未到达步骤结束点，等待用户点击候选后重跑当前步骤
        resp.put(Skill.KEY_STEP_DONE, false);
        resp.put("keyword", query);
        resp.put("options", options);
        resp.put("message", "未找到与「" + query + "」完全匹配的企业，以下是名称相似的企业：");
        return resp;
    }

    static List<Map<String, Object>> fuzzyMatchCompany(String query, Map<String, String> nameIndex) {
        if (query == null || query.isEmpty()) return List.of();

        // nameIndex mapping: credit_code → company_name
        // Build reverse index: company_name → credit_code (for lookup)
        Map<String, String> reverseIndex = new HashMap<>();
        for (var entry : nameIndex.entrySet()) {
            reverseIndex.put(entry.getValue(), entry.getKey());
        }

        // 1. Exact match (by company name)
        if (reverseIndex.containsKey(query)) {
            Map<String, Object> match = new HashMap<>();
            match.put("credit_code", reverseIndex.get(query));
            match.put("company_name", query);
            match.put("_score", 100);
            return List.of(match);
        }

        Map<String, Map<String, Object>> results = new LinkedHashMap<>();

        // 2. Multi-keyword AND matching
        String normalized = query.replace('　', ' ').replaceAll("  +", " ").trim();
        if (normalized.contains(" ")) {
            String[] keywords = normalized.split(" ");
            for (var entry : reverseIndex.entrySet()) {
                String name = entry.getKey();
                boolean allMatch = true;
                for (String kw : keywords) {
                    if (!name.contains(kw)) { allMatch = false; break; }
                }
                if (allMatch) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("credit_code", entry.getValue());
                    m.put("company_name", name);
                    m.put("_score", 80);
                    results.put(entry.getValue(), m);
                }
            }
            if (!results.isEmpty()) {
                return sortByScore(results);
            }
        }

        // 3. Character-level subsequence matching
        String cleanQuery = query.replace("有限公司", "").replace("有限责任", "")
                .replace("股份", "").replace("集团", "").replace("公司", "");
        for (var entry : reverseIndex.entrySet()) {
            String name = entry.getKey();
            String cleanName = name.replace("有限公司", "").replace("有限责任", "")
                    .replace("股份", "").replace("集团", "").replace("公司", "");
            if (cleanQuery.isEmpty() || isSubsequence(cleanQuery, cleanName)) {
                if (cleanQuery.isEmpty()) continue;
                int score;
                if (cleanQuery.equals(cleanName)) {
                    score = 95;
                } else {
                    double density = (double) query.length() / name.length();
                    score = 60 + (int) (density * 30);
                    // 简称高置信加分：核心名子序列全命中且查询覆盖企业名核心 40% 以上时视为高置信简称
                    // （如"云栖大数据"→"杭州云栖大数据技术有限公司"，否则密度公式对简称偏低无法自动匹配）
                    if (cleanQuery.length() >= cleanName.length() * 0.4) {
                        score = Math.max(score, 85);
                    }
                }
                Map<String, Object> m = new HashMap<>();
                m.put("credit_code", entry.getValue());
                m.put("company_name", name);
                m.put("_score", score);
                results.putIfAbsent(entry.getValue(), m);
            }
        }

        // 4. Simple substring match
        if (results.isEmpty()) {
            for (var entry : reverseIndex.entrySet()) {
                if (entry.getKey().contains(query)) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("credit_code", entry.getValue());
                    m.put("company_name", entry.getKey());
                    m.put("_score", 40);
                    results.put(entry.getValue(), m);
                }
            }
        }

        // 5. 宽松窗口兜底：cleanQuery 的连续子串（最长优先）包含匹配，仅作候选不做自动匹配
        //    （如"云禾科技"误输入为"云禾科支"时，窗口"云禾科"仍可命中候选供用户确认）
        if (results.isEmpty()) {
            for (int winLen = cleanQuery.length() - 1; winLen >= 2; winLen--) {
                for (int i = 0; i + winLen <= cleanQuery.length(); i++) {
                    String sub = cleanQuery.substring(i, i + winLen);
                    for (var entry : reverseIndex.entrySet()) {
                        if (entry.getKey().contains(sub)) {
                            Map<String, Object> m = new HashMap<>();
                            m.put("credit_code", entry.getValue());
                            m.put("company_name", entry.getKey());
                            m.put("_score", 45);
                            results.putIfAbsent(entry.getValue(), m);
                        }
                    }
                }
                if (!results.isEmpty()) break;
            }
        }

        return sortByScore(results);
    }

    private static boolean isSubsequence(String query, String target) {
        int qi = 0;
        for (int i = 0; i < target.length() && qi < query.length(); i++) {
            if (target.charAt(i) == query.charAt(qi)) qi++;
        }
        return qi == query.length();
    }

    private static List<Map<String, Object>> sortByScore(Map<String, Map<String, Object>> results) {
        List<Map<String, Object>> list = new ArrayList<>(results.values());
        list.sort((a, b) -> Integer.compare(
                ((Number) b.get("_score")).intValue(),
                ((Number) a.get("_score")).intValue()));
        return list;
    }
}
