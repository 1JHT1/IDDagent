package com.IDDagent.skill;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CustomerOutreachSkill {

    private static final String OUTREACH_FILE = "data/customer_outreach.json";
    private static final String NAME_INDEX_FILE = "data/company_name_index.json";

    private final SkillRegistry registry;

    public CustomerOutreachSkill(SkillRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "prepare_customer_outreach",
                "当用户询问「准备拓户材料」「准备营销材料」「准备客户触达信息」" +
                        "「营销谈资」「营销话术」「拓户准备」「准备对xx客户进行营销」时调用此技能。" +
                        "根据企业名称或统一信用代码返回客户触达渠道、营销谈资和营销话术链接。",
                this::handle,
                Map.of(
                        "credit_code", new Skill.SkillParam("string", "企业统一信用代码，18位数字+字母", false, "91110108MA01B3XK2P"),
                        "company_name", new Skill.SkillParam("string", "企业名称，用于模糊匹配", false, "北京星河科技有限公司")
                )
        ));
    }

    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();

        Map<String, Object> outreachData = DataLoader.loadJson(OUTREACH_FILE);
        String baseUrl = DataLoader.buildBaseUrl();

        if (!creditCode.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) outreachData.get(creditCode);
            if (result != null) {
                return buildResponse(result, baseUrl);
            }
            return notFound("未查询到统一信用代码为 " + creditCode + " 的企业的拓户准备资料，请核实代码是否正确。");
        }

        if (!companyName.isEmpty()) {
            Map<String, String> nameIndex = loadNameIndex();
            Map<String, Object> resolved = RiskCheckSkill.resolveCompanyMatch(companyName, nameIndex);

            if (resolved.containsKey("credit_code")) {
                String code = (String) resolved.get("credit_code");
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) outreachData.get(code);
                if (result != null) {
                    return buildResponse(result, baseUrl);
                }
                return notFound("已定位到企业，但暂无该企业的拓户准备资料。");
            }
            return resolved;
        }

        return notFound("请提供企业名称或统一信用代码进行拓户准备资料查询。");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildResponse(Map<String, Object> data, String baseUrl) {
        String code = (String) data.get("credit_code");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) data.getOrDefault("contact_channels", List.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "result");
        result.put("credit_code", code);
        result.put("company_name", data.get("company_name"));
        result.put("business_address", data.getOrDefault("business_address", ""));
        result.put("registered_address", data.getOrDefault("registered_address", ""));
        result.put("contact_channels", channels);
        result.put("insights_h5_url", baseUrl + "/h5/marketing-insights.html?code=" + code);
        result.put("script_h5_url", baseUrl + "/h5/marketing-script.html?code=" + code);
        result.put("channel_count", channels.size());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadNameIndex() {
        Map<String, Object> data = DataLoader.loadJson(NAME_INDEX_FILE);
        return (Map<String, String>) (Map<?, ?>) data;
    }

    private Map<String, Object> notFound(String message) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "not_found");
        resp.put("message", message);
        return resp;
    }
}
