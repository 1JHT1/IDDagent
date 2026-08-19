package com.IDDagent.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业信息查询类技能（通用实现）。
 * 通过 SKILL_CONFIG 统一注册 9 个查询技能实例，按 query_type 从综合数据文件读取对应数据。
 * 企业名称模糊匹配复用 {@link RiskCheckSkill#resolveCompanyMatch}。
 */
@Component
public class CompanyQuerySkill {

    private static final Logger log = LoggerFactory.getLogger(CompanyQuerySkill.class);

    private static final String QUERY_FILE = "data-template/company_query_data.json";
    private static final String NAME_INDEX_FILE = "data-template/company_name_index.json";

    /**
     * 疑问/问题句式判定（与 RiskCheckSkill 同源）：含典型疑问词（什么/怎么/是否/吗 等）或提问类
     * 名词（介绍/定义/含义/包含）时，用户在提问而非提供企业名——兜底提取必须放弃把清理后残渣当
     * 主体，否则会把问题文本直接当公司名去查询/误报"未找到企业"，应先询问主体。
     */
    private static final java.util.regex.Pattern QUESTION_PATTERN = java.util.regex.Pattern.compile(
            "什么|哪些|哪个|怎么|如何|为什么|为啥|多少|有没有|是否|是不是|嘛|呢|吗|啥|干嘛|干什么|做什么|介绍|定义|含义|意思|包含|包括");

    /** 所有查询技能共享的通用功能词/指示代词（兜底提取与参数清洗用） */
    private static final String GENERIC_QUERY_WORDS =
            "(?:帮我|请|麻烦|要|想|一下|的|关于|查|查询|看看|看下|这家公司|该公司|这家企业|该企业|这个企业)";

    /** 技能名 → 该技能功能词（兜底提取与参数清洗用：从输入中移除后剩余内容视为企业名） */
    private static final Map<String, String> SKILL_FUNCTIONAL_WORDS = new LinkedHashMap<>();

    /** 技能名 → 数据字段名（query_type） */
    private static final Map<String, String> SKILL_CONFIG = new LinkedHashMap<>();
    /** 技能名 → 中文标签，用于提示语与候选选择消息 */
    private static final Map<String, String> SKILL_LABEL = new LinkedHashMap<>();
    /** 技能名 → 关键词别名（模糊意图识别兜底用；兜底技能 query_company_basic_info 少给别名，避免过度捕获） */
    private static final Map<String, List<String>> SKILL_ALIASES = new LinkedHashMap<>();

    static {
        SKILL_CONFIG.put("query_company_basic_info", "basic_info");
        SKILL_CONFIG.put("query_shareholder_info", "shareholders");
        SKILL_CONFIG.put("query_beneficiary_info", "beneficiaries");
        SKILL_CONFIG.put("query_company_genealogy", "genealogy");
        SKILL_CONFIG.put("query_customs_auth", "customs_auth");
        SKILL_CONFIG.put("query_customs_blacklist", "customs_blacklist");
        SKILL_CONFIG.put("query_account_freeze_tag", "freeze_tags");
        SKILL_CONFIG.put("query_credit_granting", "credit_granting");
        SKILL_CONFIG.put("query_pboc_account_control", "pboc_account_control");

        SKILL_LABEL.put("query_company_basic_info", "基本信息");
        SKILL_LABEL.put("query_shareholder_info", "股东信息");
        SKILL_LABEL.put("query_beneficiary_info", "受益人信息");
        SKILL_LABEL.put("query_company_genealogy", "企业族谱");
        SKILL_LABEL.put("query_customs_auth", "海关认证信息");
        SKILL_LABEL.put("query_customs_blacklist", "海关失信名单信息");
        SKILL_LABEL.put("query_account_freeze_tag", "账户冻结标签");
        SKILL_LABEL.put("query_credit_granting", "授信信息");
        SKILL_LABEL.put("query_pboc_account_control", "人行账户管控信息");

        SKILL_ALIASES.put("query_shareholder_info", List.of("股东", "股权结构", "股权", "股东名单", "持股情况"));
        SKILL_ALIASES.put("query_beneficiary_info", List.of("受益人", "实际控制人", "实控人", "最终受益人", "背后老板"));
        SKILL_ALIASES.put("query_company_genealogy", List.of("企业族谱", "家族图谱", "关联图谱", "关联企业", "集团图谱"));
        SKILL_ALIASES.put("query_customs_auth", List.of("海关认证", "海关高级认证", "AEO认证"));
        SKILL_ALIASES.put("query_customs_blacklist", List.of("海关失信", "海关黑名单", "海关失信名单"));
        SKILL_ALIASES.put("query_account_freeze_tag", List.of("冻结", "账户冻结", "司法冻结"));
        SKILL_ALIASES.put("query_credit_granting", List.of("授信", "授信额度", "信贷额度", "额度多少"));
        SKILL_ALIASES.put("query_pboc_account_control", List.of("人行账管", "账户管控", "央行账户管理"));
        SKILL_ALIASES.put("query_company_basic_info", List.of("基本信息", "工商信息"));

        // 功能词表与 SKILL_ALIASES 同源并扩充：用户输入/LLM 参数中这些词都是功能描述而非企业名
        SKILL_FUNCTIONAL_WORDS.put("query_company_basic_info", "基本信息|基本资料|工商信息|企业信息|公司信息");
        SKILL_FUNCTIONAL_WORDS.put("query_shareholder_info", "股东信息|股东|股权结构|股权|股东名单|持股情况|持股|股份");
        SKILL_FUNCTIONAL_WORDS.put("query_beneficiary_info", "受益人|受益所有人|实际控制人|实控人|最终受益人|背后老板|受益者");
        SKILL_FUNCTIONAL_WORDS.put("query_company_genealogy", "企业族谱|家族图谱|关联图谱|关联企业|集团图谱|关联方|家族企业|企业图谱|族谱");
        SKILL_FUNCTIONAL_WORDS.put("query_customs_auth", "海关认证|海关高级认证|AEO认证|高级认证|认证信息");
        SKILL_FUNCTIONAL_WORDS.put("query_customs_blacklist", "海关失信|海关黑名单|海关失信名单|失信名单|黑名单|失信记录");
        SKILL_FUNCTIONAL_WORDS.put("query_account_freeze_tag", "账户冻结|冻结|司法冻结|冻结标签");
        SKILL_FUNCTIONAL_WORDS.put("query_credit_granting", "授信|授信额度|信贷额度|额度|贷款额度");
        SKILL_FUNCTIONAL_WORDS.put("query_pboc_account_control", "人行账管|账户管控|央行账户管理|人行账户管控|账户管理|人行账户");
    }

    private final SkillRegistry registry;

    public CompanyQuerySkill(SkillRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        for (String skillName : SKILL_CONFIG.keySet()) {
            String label = SKILL_LABEL.get(skillName);
            // 用 lambda 绑定技能名，共享 handle 实现
            registry.register(new Skill(
                    skillName,
                    "当用户查询法人企业的" + label + "时调用此技能。根据企业统一信用代码或企业名称查询" + label + "并返回查询结果。",
                    (userId, params) -> handle(skillName, userId, params),
                    Map.of(
                            "credit_code", new Skill.SkillParam("string", "企业统一信用代码，18位数字+字母", false, "91110108MA01B3XK2P"),
                            "company_name", new Skill.SkillParam("string", "企业名称，用于模糊匹配", false, "北京星河科技有限公司")
                    )
            ), SKILL_ALIASES.getOrDefault(skillName, List.of()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handle(String skillName, String userId, Map<String, Object> params) {
        String queryType = SKILL_CONFIG.get(skillName);
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();

        // 从 _user_input 兜底提取企业标识（与 RiskCheckSkill/InformationCheckSkill 一致）：
        // pending 路径（info_needed 后用户补充企业名，如回复"小米"）完全跳过 LLM，复用第一轮
        // 的空 params 只注入 _user_input，企业名仅存在于原始输入中——若不兜底提取，会再次误报
        // "请提供企业名称或统一信用代码进行XX查询"而死循环。Coordinator 路由路径同样注入 _user_input。
        String userInput = ((String) params.getOrDefault("_user_input", "")).trim();
        // 多意图规划标记（buildPlan 注入）：多意图句子（如"查下小米的风险和股东信息"）中 LLM 可能把
        // 功能词残渣（"股东信息"）填进 company_name。标记生效时：有主体 → 由参数清洗结合功能词判定
        // 清洗；无主体 → 跳过 _user_input 兜底提取（多意图句子本身不是企业名），直接询问主体。
        boolean fromMultiIntent = Boolean.TRUE.equals(params.get("_from_multi_intent"));

        // 参数 company_name 功能词清洗：意图识别 LLM 在"必须提取企业名称"的强指令下，对纯功能句/
        // 多意图混合句（如"查询股东信息"）会猜测填充非企业名的垃圾值（如"股东信息"）。这类值直接
        // 拿去匹配会误报"未找到企业"且不询问主体。清洗功能词残渣：剩余 <2 字或判定为功能词残渣 →
        // 视为未提供主体（清空后走下方兜底提取/询问主体）；剩余 ≥2 字且非残渣 → 用清洗结果替换。
        if (creditCode.isEmpty() && !companyName.isEmpty()) {
            String cleaned = companyName
                    .replaceAll(SKILL_FUNCTIONAL_WORDS.getOrDefault(skillName, ""), "")
                    .replaceAll(GENERIC_QUERY_WORDS, "")
                    .replaceAll("[，。；、！？!?\\s：:（）()]+", "")
                    .trim();
            if (cleaned.length() < 2 || isFunctionalResidue(cleaned)) {
                log.info("CompanyQuerySkill 参数 company_name 疑似功能句垃圾值/功能残渣，清空以询问主体: '{}' (input='{}')",
                        companyName, userInput);
                companyName = "";
            } else if (!cleaned.equals(companyName)) {
                log.info("CompanyQuerySkill 清洗参数 company_name 功能词残渣: '{}' → '{}'",
                        companyName, cleaned);
                companyName = cleaned;
            }
        }

        // 非多意图、无主体时从 _user_input 兜底提取企业标识（pending 第二轮用户补充企业名，如回复"小米"）
        if (!fromMultiIntent && creditCode.isEmpty() && companyName.isEmpty() && !userInput.isEmpty()) {
            // 1) 18 位统一信用代码
            java.util.regex.Matcher ccMatcher = java.util.regex.Pattern
                    .compile("[0-9A-Z]{18}").matcher(userInput.toUpperCase());
            if (ccMatcher.find()) {
                creditCode = ccMatcher.group();
            } else {
                // 2) 通用兜底：移除该技能功能词/通用查询词后剩余内容视为企业名
                String cleaned = userInput
                        .replaceAll(SKILL_FUNCTIONAL_WORDS.getOrDefault(skillName, ""), "")
                        .replaceAll(GENERIC_QUERY_WORDS, "")
                        .replaceAll("[，。；、！？!?\\s：:（）()]+", "")
                        .trim();
                // 最终防线：清理后若仅剩"下/查/公司/这家"等纯功能残渣（如"查股东信息"清理后为空、
                // "这家公司"清理后为指示代词），视为无企业名 → 下方询问主体
                if (cleaned.matches("(?:一下|一遍|下|遍|帮我|请|麻烦|要|想|的|关于|查|查询|公司|这家|该企业|该)+")) {
                    cleaned = "";
                }
                // 问题句式防护：清理后残余若仍含疑问词（"股东信息包括哪些内容"→"包括哪些内容"），
                // 说明用户在提问而非提供企业名，放弃提取 → 下方询问主体
                if (!cleaned.isEmpty() && QUESTION_PATTERN.matcher(cleaned).find()) {
                    log.info("CompanyQuerySkill 清理结果疑似问题句式，放弃提取: '{}' (input='{}')",
                            cleaned, userInput);
                    cleaned = "";
                }
                if (cleaned.length() >= 2 && !isFunctionalResidue(cleaned)) {
                    companyName = cleaned;
                    log.info("CompanyQuerySkill 从 _user_input 兜底提取企业名称: '{}' (input='{}')",
                            companyName, userInput);
                }
            }
        }

        // 统一信用代码直接查询
        if (!creditCode.isEmpty()) {
            return buildResult(skillName, queryType, creditCode);
        }

        // 企业名称 → 信用代码（复用风险预查的模糊匹配）
        if (!companyName.isEmpty()) {
            Map<String, String> nameIndex = loadNameIndex();
            Map<String, Object> resolved = RiskCheckSkill.resolveCompanyMatch(companyName, nameIndex);
            if (resolved.containsKey("credit_code")) {
                return buildResult(skillName, queryType, (String) resolved.get("credit_code"));
            }
            // ambiguous / not_found（带 options 候选）→ 透传给前端，附查询标签供候选按钮拼消息
            Map<String, Object> resp = new LinkedHashMap<>(resolved);
            resp.put("query_type", queryType);
            resp.put("query_label", SKILL_LABEL.get(skillName));
            return resp;
        }

        // 缺少企业标识 → 提示补齐（中间态，保留 pending skill）：明确告知用户需提供公司名或
        // 统一信用代码，并附示例引导回复（企业名/18 位码两种提供方式都可），避免用户不知道该回答什么
        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "info_needed");
        // 未到达步骤结束点：等待用户补充企业标识后重跑当前步骤
        resp.put(Skill.KEY_STEP_DONE, false);
        resp.put("message", "查询" + SKILL_LABEL.get(skillName) + "需要提供企业名称或统一信用代码。您可以直接回复企业名称，例如：北京星河科技有限公司；或提供 18 位统一信用代码，例如：91110108MA01B3XK2P。");
        resp.put("company_name", companyName);
        resp.put("credit_code", creditCode);
        return resp;
    }

    /**
     * 判定提取/清洗结果是否为"功能词残渣"而非真实企业名：
     * - 纯功能词组合（"股东信息""授信额度"），如多意图句子中 LLM 把"股东信息"填进 company_name；
     * - 多意图功能句残渣：如"股东信息和风险识别"兜底提取到的"和风险识别"，剥掉首尾连接词后
     *   仍为功能词组合；
     * - 问题句残渣：含疑问词（"是什么""包括哪些内容"）。
     * 真实企业名（含功能字的"XX信息科技有限公司"）含词表外的字（企业名主体），不受影响。
     * 与 RiskCheckSkill.isFunctionalResidue 同源（词表保持一致）。
     */
    private static boolean isFunctionalResidue(String s) {
        if (s == null || s.isEmpty()) return true;
        String stripped = s.replaceFirst("^(?:和|并|及|或|与|以及)+", "")
                .replaceFirst("(?:和|并|及|或|与|以及)+$", "");
        if (stripped.isEmpty()) return true;
        if (QUESTION_PATTERN.matcher(stripped).find()) return true;
        return stripped.matches("(?:信息|股东|股权|持股|受益人|实控人|族谱|关联|海关|认证|失信|黑名单|冻结|授信|额度|管控|账管|企业|公司|查询|检索|搜索|一下|一遍|下|遍|帮我|请|麻烦|要|想|的|关于|这家|该)+");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildResult(String skillName, String queryType, String creditCode) {
        Map<String, Object> queryData = DataLoader.loadJson(QUERY_FILE);
        Object companyRaw = queryData.get(creditCode);
        if (!(companyRaw instanceof Map)) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            resp.put(Skill.KEY_STEP_DONE, true);
            resp.put("message", "未查询到统一信用代码为 " + creditCode + " 的企业" + SKILL_LABEL.get(skillName) + "，请核实代码是否正确。");
            return resp;
        }

        Map<String, Object> company = (Map<String, Object>) companyRaw;
        Object data = company.get(queryType);
        if (data == null) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            resp.put(Skill.KEY_STEP_DONE, true);
            resp.put("message", "未查询到「" + company.getOrDefault("company_name", creditCode) + "」的" + SKILL_LABEL.get(skillName) + "。");
            return resp;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "result");
        // 到达步骤结束点：查询结果已返回，步骤完成
        result.put(Skill.KEY_STEP_DONE, true);
        result.put("query_type", queryType);
        result.put("query_label", SKILL_LABEL.get(skillName));
        result.put("credit_code", creditCode);
        result.put("company_name", company.getOrDefault("company_name", ""));
        result.put("data", data);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadNameIndex() {
        Map<String, Object> data = DataLoader.loadJson(NAME_INDEX_FILE);
        return (Map<String, String>) (Map<?, ?>) data;
    }
}
