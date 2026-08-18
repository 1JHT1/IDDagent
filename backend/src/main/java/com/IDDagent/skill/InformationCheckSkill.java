package com.IDDagent.skill;

import com.IDDagent.service.CompanyNameExtractor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class InformationCheckSkill {

    private static final Logger log = LoggerFactory.getLogger(InformationCheckSkill.class);

    private static final String INFO_CHECK_FILE = "data-template/information_check.json";
    private static final String NAME_INDEX_FILE = "data-template/company_name_index.json";
    /** _user_input 清洗用技能动词/查询后缀（供 CompanyNameExtractor 统一清洗链） */
    private static final String INFO_VERBS = "核实|核验|核查|验证|查询|查一下|查";
    private static final String INFO_SUFFIXES = "的营业执照|营业执照|的核实|的核查|的资料|的信息|核实|核查|资料|信息";

    /**
     * 从用户原始输入中提取"核实行为词 + 企业名"结构的企业名称（如"核实小米" → "小米"、
     * "帮我核实一下北京星河科技有限公司" → "北京星河科技有限公司"）。
     * lookahead 以"的/企业/营业执照"或行尾锚定企业名边界，非贪婪下不会吞入后续内容。
     * 注意：锚点不能含"公司"——"公司"是企业名的一部分，含它会把"北京星河科技有限公司"
     * 截成"北京星河科技有限"；可选组"(?:一下|下|一遍)?"在输入以功能词收尾（如"核实一下"）
     * 时会回溯放弃匹配，让捕获组吞掉"一下"，因此提取后需排除"一下/一遍"等纯功能残渣。
     */
    private static final java.util.regex.Pattern VERIFY_COMPANY_PATTERN = java.util.regex.Pattern.compile(
            "(?:帮我)?(?:核实|核验|核查|验证)(?:一下|下|一遍)?\\s*" +
            "([^，。；、\\s]{2,30}?)(?=的|企业|营业执照|$)");

    /**
     * 疑问/问题句式判定：含典型疑问词（什么/怎么/是否/有没有/吗/呢 等）或提问类名词
     * （介绍/定义/含义/意思/包含/包括）。用户以问题句式表达时（如"信息核实是什么"、
     * "信息核实包括哪些内容"）是在提问而非提供企业名——兜底提取必须放弃把残渣当主体，
     * 否则会把问题文本直接当公司名去查询/误报"未找到企业"，应先询问主体。
     * 真实企业名不含疑问词，不受影响。
     * 与 IntentPlannerService/RiskCheckSkill/HistoricalDDQuerySkill 的 QUESTION_PATTERN 同源。
     */
    private static final java.util.regex.Pattern QUESTION_PATTERN = java.util.regex.Pattern.compile(
            "什么|哪些|哪个|怎么|如何|为什么|为啥|多少|有没有|是否|是不是|嘛|呢|吗|啥|干嘛|干什么|做什么|介绍|定义|含义|意思|包含|包括");

    /**
     * 判定提取结果是否为"功能句残渣"而非真实企业名：
     * - 纯功能词（"信息"、"一下"等），如"核实信息"提取到的"信息"；
     * - 多意图功能句残渣：如"信息核实和风险识别"中 VERIFY_COMPANY_PATTERN 的 lookahead $ 会把
     *   "和风险识别"当企业名，残渣以连接词（和/并/及/或/与/以及）开头，去掉连接词后仍为功能词组合；
     * - 问题句残渣：如"核实一下是什么"中可选组回溯会把"是什么"当企业名，含疑问词即判为残渣。
     * 真实企业名（含"小米"这类简称）不含功能词组合/疑问词，不受影响。
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

    private final SkillRegistry registry;

    public InformationCheckSkill(SkillRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "verify_business_license",
                "当用户上传营业执照附件并表示要核实信息、信息核查、营业执照核实时调用此技能。" +
                        "从营业执照图片中提取参数并与权威数据源逐项核实，返回核实结论和详细报告。",
                this::handle,
                Map.of(
                        "credit_code", new Skill.SkillParam("string", "企业统一信用代码，18位数字+字母", false, "91110108MA01B3XK2P"),
                        "company_name", new Skill.SkillParam("string", "企业名称，用于自动匹配信用代码", false, "北京星河科技有限公司"),
                        "_attachment_url", new Skill.SkillParam("string", "上传的营业执照附件URL（系统内部传递）", false, "")
                )
        ), List.of("核实信息", "信息核实", "营业执照核实", "核实下", "查下执照", "执照真假", "核验"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();

        // 从 _user_input 兜底提取企业标识（与 HistoricalDDQuerySkill 阶段一前置一致）：
        // pending 路径（info_needed 后用户补充企业名，如回复"小米"）完全跳过 LLM，复用第一轮
        // 的空 params 只注入 _user_input，企业名仅存在于原始输入中——若不兜底提取，会再次误报
        // "请提供企业名称或统一信用代码进行信息核实"而死循环。Coordinator 路由路径同样注入 _user_input。
        String userInput = ((String) params.getOrDefault("_user_input", "")).trim();

        // 多意图规划标记（buildPlan 注入）：多意图句子（如"风险识别和信息核实"）中 LLM 可能把
        // 功能词残渣（"信息核实"）填进 company_name，兜底提取也可能把多意图句子的残余
        // （"风险识别和"）当公司名——都不是用户提供的企业主体。标记生效时：
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
        // （名称提取被 else if (companyName.isEmpty()) 分支挡掉，只提取码不提取名），清空后占位已丢失、
        // 无从再提取，只能反复询问"请提供企业名称或统一信用代码进行信息核实"而死循环。
        // 先清占位、再从用户真实输入提取，形成自救链路。用户输入中含该名称（用户直接提供）时保留，
        // 正常走解析。credit_code 不做该校验：18 位代码只可能来自用户输入或上一轮解析结果
        // （buildPlan/handleSingleSkill 已跳过 ctx 预补全），不会被凭空伪造。
        if (creditCode.isEmpty() && !companyName.isEmpty() && !userInput.contains(companyName) && !broadcastSubject) {
            log.info("InformationCheckSkill 主体非用户本次提供，清空后从 _user_input 重新提取: '{}' (input='{}')",
                    companyName, userInput);
            companyName = "";
        }

        // 多意图主体清洗：LLM 把多意图句子的功能词残渣填进 company_name（如"信息核实"、
        // "风险识别和信息核实"中的"信息核实"）时，先按与 _user_input 兜底一致的词表清洗；
        // 清洗后为空或仍为功能词残渣（如"风险识别和"）→ 视为未提供主体，后续直接询问。
        if (fromMultiIntent && creditCode.isEmpty() && !companyName.isEmpty() && !broadcastSubject) {
            String cleaned = companyName
                    .replaceAll("核实信息|信息核实|信息核查|营业执照核实|营业执照核验|信息核验|核查信息|核验信息|营业执照", "")
                    .replaceAll("核实|核验|核查|验证|执照", "")
                    .replaceAll("(?:帮我|请|麻烦|要|想|一下|的|关于)", "")
                    .replaceAll("[，。；、！？!?\\s：:（）()]+", "")
                    .trim();
            if (cleaned.isEmpty() || isFunctionalResidue(cleaned)) {
                log.info("InformationCheckSkill 多意图主体清洗为功能残渣，清空以询问主体: '{}' (input='{}')",
                        companyName, userInput);
                companyName = "";
            } else if (!cleaned.equals(companyName)) {
                log.info("InformationCheckSkill 清洗多意图主体功能词残渣: '{}' → '{}'", companyName, cleaned);
                companyName = cleaned;
            }
        }

        // 多意图无主体时跳过 _user_input 兜底提取：多意图句子本身是功能描述而非企业名，
        // 提取必然得到残余（如"风险识别和信息核实" → "风险识别和"），应询问主体而非查询
        if (!fromMultiIntent && creditCode.isEmpty() && !userInput.isEmpty()) {
            // 1) 18 位统一信用代码
            java.util.regex.Matcher ccMatcher = java.util.regex.Pattern
                    .compile("[0-9A-Z]{18}").matcher(userInput.toUpperCase());
            if (ccMatcher.find()) {
                creditCode = ccMatcher.group();
            } else if (companyName.isEmpty()) {
                // 2) "核实XX"结构提取
                String extracted = "";
                java.util.regex.Matcher qm = VERIFY_COMPANY_PATTERN.matcher(userInput);
                if (qm.find()) {
                    extracted = qm.group(1).trim();
                    // 排除功能句本身被误提取："核实信息"会提取到"信息"；"核实一下"/"我要核实一下"
                    // 中可选组回溯放弃匹配时捕获组会吞掉"一下"（正则自身无法区分）；多意图功能句
                    // "信息核实和风险识别"中 lookahead $ 会把"和风险识别"当企业名，也一并过滤
                    if (isFunctionalResidue(extracted)) extracted = "";
                }
                if (extracted.isEmpty()) {
                    // 3) 通用兜底：移除核实行为词/功能词后剩余内容视为企业名（如 pending 第二轮直接回复"小米"）
                    String cleaned = userInput
                            .replaceAll("核实信息|信息核实|信息核查|营业执照核实|营业执照核验|信息核验|核查信息|核验信息|营业执照", "")
                            .replaceAll("核实|核验|核查|验证|执照", "")
                            .replaceAll("(?:帮我|请|麻烦|要|想|一下|的|关于)", "")
                            .replaceAll("[，。；、！？!?\\s：:（）()]+", "")
                            .trim();
                    // 最终防线：清理后若仅剩"一下/一遍/帮我/请"等纯功能残渣（如"核实一下"）、或多意图
                    // 功能句残渣（如"和风险识别"，连接词+功能词组合），视为无企业名
                    if (cleaned.matches("(?:一下|一遍|下|遍|帮我|请|麻烦|要|想|的|关于)+")) cleaned = "";
                    if (cleaned.length() >= 2 && !isFunctionalResidue(cleaned)) extracted = cleaned;
                }
                if (!extracted.isEmpty()) {
                    companyName = extracted;
                    log.info("InformationCheckSkill 从 _user_input 兜底提取企业名称: '{}' (input='{}')",
                            companyName, userInput);
                }
            }
        }

        // 解析公司名称 → 信用代码
        if (creditCode.isEmpty() && !companyName.isEmpty()) {
            Map<String, String> nameIndex = (Map<String, String>) (Map<?, ?>) DataLoader.loadJson(NAME_INDEX_FILE);
            Map<String, Object> resolved = RiskCheckSkill.resolveCompanyMatch(companyName, nameIndex);

            // ambiguous（多匹配）或 not_found（有相似企业）直接返回给前端
            if (resolved.containsKey("action")) {
                return resolved;
            }

            if (resolved.containsKey("credit_code")) {
                creditCode = (String) resolved.get("credit_code");
                companyName = nameIndex.getOrDefault(creditCode, companyName);
            }
        }

        if (creditCode.isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "info_needed");
            // 未到达步骤结束点：等待用户补充企业标识后重跑当前步骤
            resp.put(Skill.KEY_STEP_DONE, false);
            resp.put("message", "请提供企业名称或统一信用代码进行信息核实。");
            return resp;
        }

        // 检查是否有上传附件
        String attachmentUrl = ((String) params.getOrDefault("_attachment_url", "")).trim();
        if (attachmentUrl.isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "info_needed");
            // 未到达步骤结束点：等待用户上传营业执照后重跑当前步骤
            resp.put(Skill.KEY_STEP_DONE, false);
            resp.put("message", "请上传该企业的营业执照图片以进行信息核实。");
            // 带回已解析的企业信息，供 ChatController 合并进技能上下文，避免下一轮参数丢失
            resp.put("company_name", companyName);
            resp.put("credit_code", creditCode);
            return resp;
        }

        // 加载参考数据（预设的"正确答案"）
        Map<String, Object> checkData = DataLoader.loadJson(INFO_CHECK_FILE);
        Map<String, Object> raw = (Map<String, Object>) checkData.get(creditCode);

        if (raw == null) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            // 到达步骤结束点：查询无数据，步骤完成
            resp.put(Skill.KEY_STEP_DONE, true);
            resp.put("message", "未查询到信用代码为 " + creditCode + " 的企业核实数据。");
            return resp;
        }

        // 直接从 JSON 读取 mock 数据
        List<Map<String, Object>> items = mockExtractBusinessLicense(raw);

        // 统计
        int passCount = 0, failCount = 0, noneCount = 0;
        for (Map<String, Object> item : items) {
            Boolean pass = (Boolean) item.get("pass");
            if (pass == null) noneCount++;
            else if (pass) passCount++;
            else failCount++;
        }

        // 构建返回
        return buildResult(raw, items, creditCode, passCount, failCount, noneCount);
    }

    // ============================================================
    // Mock 模式
    // ============================================================

    /**
     * 模拟从营业执照图片中提取参数（直接读 JSON 数据）。
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mockExtractBusinessLicense(Map<String, Object> raw) {
        Map<String, Object> details = (Map<String, Object>) raw.get("details");
        if (details == null) return List.of();

        List<Map<String, Object>> sourceItems = (List<Map<String, Object>>) details.get("items");
        if (sourceItems == null) return List.of();

        List<Map<String, Object>> extracted = new ArrayList<>();
        for (Map<String, Object> item : sourceItems) {
            Map<String, Object> ei = new LinkedHashMap<>();
            ei.put("name", item.get("name"));
            ei.put("value", item.get("value"));
            ei.put("pass", item.get("pass"));
            ei.put("label", item.getOrDefault("label", ""));
            extracted.add(ei);
        }
        return extracted;
    }

    // ============================================================
    // 结果构建 & H5 标准化
    // ============================================================

    private Map<String, Object> buildResult(Map<String, Object> raw,
                                            List<Map<String, Object>> items,
                                            String creditCode,
                                            int passCount, int failCount, int noneCount) {
        String baseUrl = DataLoader.buildBaseUrl();
        Map<String, Object> details = DataLoader.getMap(raw, "details");
        String detailsName = (String) details.getOrDefault("name", "");

        // 从 items 中取企业名称
        String companyName = "";
        for (Map<String, Object> item : items) {
            if ("企业名称".equals(item.get("name"))) {
                String v = (String) item.get("value");
                if (v != null && !v.isEmpty()) {
                    companyName = v;
                    break;
                }
            }
        }
        // 如果未从items获取到，从参考数据(raw)中获取
        if (companyName.isEmpty()) {
            Map<String, Object> d = DataLoader.getMap(raw, "details");
            if (d != null) {
                List<Map<String, Object>> srcItems = (List<Map<String, Object>>) d.get("items");
                if (srcItems != null) {
                    for (Map<String, Object> si : srcItems) {
                        if ("企业名称".equals(si.get("name"))) {
                            companyName = (String) si.get("value");
                            break;
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "result");
        // 到达步骤结束点：核实结果已返回，步骤完成
        result.put(Skill.KEY_STEP_DONE, true);
        result.put("credit_code", creditCode);
        result.put("company_name", companyName);
        result.put("details_name", detailsName);
        result.put("total_count", items.size());
        result.put("pass_count", passCount);
        result.put("fail_count", failCount);
        result.put("none_count", noneCount);
        result.put("h5_url", baseUrl + "/h5/information-check.html?code=" + creditCode);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeForH5(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> details = (Map<String, Object>) raw.get("details");
        String detailsName = details != null ? (String) details.get("name") : "";

        String companyName = "";
        String creditCode = "";
        List<Map<String, Object>> items = new ArrayList<>();
        if (details != null) {
            List<Map<String, Object>> sourceItems = (List<Map<String, Object>>) details.get("items");
            if (sourceItems != null) {
                for (Map<String, Object> si : sourceItems) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    String name = (String) si.get("name");
                    String value = (String) si.get("value");
                    item.put("name", name);
                    item.put("value", value != null ? value : "");
                    item.put("pass", si.get("pass"));
                    item.put("label", si.getOrDefault("label", ""));

                    if ("企业名称".equals(name)) companyName = value;
                    if ("统一社会信用代码".equals(name)) creditCode = value;
                    items.add(item);
                }
            }
        }

        int passCount = 0, failCount = 0, noneCount = 0;
        for (Map<String, Object> item : items) {
            Boolean pass = (Boolean) item.get("pass");
            if (pass == null) noneCount++;
            else if (pass) passCount++;
            else failCount++;
        }

        result.put("company_name", companyName);
        result.put("credit_code", creditCode);
        result.put("details_name", detailsName);
        result.put("total_count", items.size());
        result.put("pass_count", passCount);
        result.put("fail_count", failCount);
        result.put("none_count", noneCount);
        result.put("items", items);
        return result;
    }
}
