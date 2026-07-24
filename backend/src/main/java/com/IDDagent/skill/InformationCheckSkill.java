package com.IDDagent.skill;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class InformationCheckSkill {

    private static final String INFO_CHECK_FILE = "data-template/information_check.json";
    private static final String NAME_INDEX_FILE = "data-template/company_name_index.json";

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
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();

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
            resp.put("action", "not_found");
            resp.put("message", "请提供企业名称或统一信用代码进行信息核实。");
            return resp;
        }

        // 加载参考数据（预设的"正确答案"）
        Map<String, Object> checkData = DataLoader.loadJson(INFO_CHECK_FILE);
        Map<String, Object> raw = (Map<String, Object>) checkData.get(creditCode);

        if (raw == null) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
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
