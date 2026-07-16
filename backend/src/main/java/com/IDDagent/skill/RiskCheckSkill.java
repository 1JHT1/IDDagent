package com.IDDagent.skill;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RiskCheckSkill {

    private static final String RISK_FILE = "data/risk_check.json";
    private static final String NAME_INDEX_FILE = "data/company_name_index.json";
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

    private Map<String, Object> buildResult(Map<String, Object> data) {
        String baseUrl = DataLoader.buildBaseUrl();
        String code = (String) data.get("credit_code");
        Map<String, Object> result = new HashMap<>();
        result.put("action", "result");
        result.put("credit_code", code);
        result.put("company_name", data.get("company_name"));
        result.put("has_risk", data.get("has_risk"));
        result.put("risk_level", data.get("risk_level"));
        result.put("risk_summary", data.get("risk_summary"));
        result.put("h5_url", baseUrl + "/h5/risk-report.html?code=" + code);
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
