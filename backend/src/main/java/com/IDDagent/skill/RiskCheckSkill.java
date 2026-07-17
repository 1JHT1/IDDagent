package com.IDDagent.skill;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RiskCheckSkill {

    private static final String RISK_FILE = "data-template/risk_check.json";
    private static final String NAME_INDEX_FILE = "data-template/company_name_index.json";
    private static final int MIN_AUTO_MATCH_SCORE = 80;
    private static final int MAX_SUGGESTIONS = 3;

    private final SkillRegistry registry;

    public RiskCheckSkill(SkillRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "check_company_risk",
                "当用户询问查询企业开户风险、风险预查、企业风险筛查、" +
                        "查询xx客户是否存在开户风险、风险预检时调用此技能。" +
                        "根据企业统一信用代码或企业名称查询风险信息，返回风险结论和详细风险报告链接。",
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

        Map<String, Object> riskData = DataLoader.loadJson(RISK_FILE);

        if (!creditCode.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) riskData.get(creditCode);
            if (result != null) {
                return buildResult(result);
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
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
            }

            return resolved;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "not_found");
        resp.put("message", "请提供企业名称或统一信用代码进行查询。");
        return resp;
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
        result.put("credit_code", code);
        result.put("company_name", companyName);
        result.put("has_risk", hasRisk);
        result.put("risk_level", riskLevel);
        result.put("risk_summary", data.get("risk_summary"));
        result.put("h5_url", baseUrl + "/h5/risk-report.html?code=" + code);
        return result;
    }

    /**
     * 根据 details 中的各项指标计算风险等级。
     * - aml 中 has_risk=true 且 result 为 "异常"/"命中"/"严重" 计为高风险项
     * - rongan 中 riskLevel="high" 计为高风险项
     * - aml 中 has_risk=true 且 result 为 "关注" 计为中风险项
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
                        String result = (String) item.get("result");
                        if ("异常".equals(result) || "命中".equals(result) || "严重".equals(result)) {
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
    private static final Map<String, String> RONGAN_LEVEL_MAP = Map.of(
            "high", "高风险",
            "medium", "中风险",
            "low", "低风险"
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
                        ni.put("result", RONGAN_LEVEL_MAP.getOrDefault(rl != null ? rl.toLowerCase() : "", rl != null ? rl : "未知"));
                        ni.put("has_risk", "high".equalsIgnoreCase(rl) || "medium".equalsIgnoreCase(rl));
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
            resp.put("keyword", query);
            resp.put("options", options);
            resp.put("message", "搜索到 " + matches.size() + " 家与「" + query + "」匹配的企业，请确认要查询哪一家：");
            return resp;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "not_found");
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
            if (isSubsequence(cleanQuery, cleanName)) {
                int score;
                if (cleanQuery.equals(cleanName)) {
                    score = 95;
                } else {
                    double density = (double) query.length() / name.length();
                    score = 60 + (int) (density * 30);
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
