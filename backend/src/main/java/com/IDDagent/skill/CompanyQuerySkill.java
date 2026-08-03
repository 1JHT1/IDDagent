package com.IDDagent.skill;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 企业信息查询类技能（通用实现）。
 * 通过 SKILL_CONFIG 统一注册 9 个查询技能实例，按 query_type 从综合数据文件读取对应数据。
 * 企业名称模糊匹配复用 {@link RiskCheckSkill#resolveCompanyMatch}。
 */
@Component
public class CompanyQuerySkill {

    private static final String QUERY_FILE = "data-template/company_query_data.json";
    private static final String NAME_INDEX_FILE = "data-template/company_name_index.json";

    /** 技能名 → 数据字段名（query_type） */
    private static final Map<String, String> SKILL_CONFIG = new LinkedHashMap<>();
    /** 技能名 → 中文标签，用于提示语与候选选择消息 */
    private static final Map<String, String> SKILL_LABEL = new LinkedHashMap<>();

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
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handle(String skillName, String userId, Map<String, Object> params) {
        String queryType = SKILL_CONFIG.get(skillName);
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();

        // 多轮交互：企业名与信用代码均未由 LLM 提供时，从用户下一条输入中提取企业名
        if (creditCode.isEmpty() && companyName.isEmpty()) {
            String userInput = ((String) params.getOrDefault("_user_input", "")).trim();
            if (!userInput.isEmpty()) {
                String cleaned = userInput
                        .replaceAll("^(请帮我|帮我|请|麻烦|辛苦)\\s*(查询|查一下|查|看看|看一下|提供|获取|核实)\\s*(一下|下)?\\s*", "")
                        .replaceAll("^(查询|查一下|查|看看|看一下)\\s*", "")
                        .replaceAll("\\s*(的股东信息|的受益人信息|的基本信息|的授信信息|的企业族谱|的海关认证信息|的海关失信记录|的账户冻结标签|的授信情况|的人行账户管控情况|的信息|的资料|的情况|情况|资料)$", "")
                        .trim();
                if (!cleaned.isEmpty() && cleaned.length() <= 100) {
                    companyName = cleaned;
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

        // 缺少企业标识 → 提示补齐（中间态，保留 pending skill）
        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "info_needed");
        resp.put("message", "请提供企业名称或统一信用代码进行" + SKILL_LABEL.get(skillName) + "查询。");
        resp.put("company_name", companyName);
        resp.put("credit_code", creditCode);
        return resp;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildResult(String skillName, String queryType, String creditCode) {
        Map<String, Object> queryData = DataLoader.loadJson(QUERY_FILE);
        Object companyRaw = queryData.get(creditCode);
        if (!(companyRaw instanceof Map)) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            resp.put("message", "未查询到统一信用代码为 " + creditCode + " 的企业" + SKILL_LABEL.get(skillName) + "，请核实代码是否正确。");
            return resp;
        }

        Map<String, Object> company = (Map<String, Object>) companyRaw;
        Object data = company.get(queryType);
        if (data == null) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            resp.put("message", "未查询到「" + company.getOrDefault("company_name", creditCode) + "」的" + SKILL_LABEL.get(skillName) + "。");
            return resp;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "result");
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
