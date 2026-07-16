package com.IDDagent.skill;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ProductRecommendSkill {

    private static final String PRODUCT_FILE = "data/product_recommendations.json";
    private static final String NAME_INDEX_FILE = "data/company_name_index.json";
    private static final Map<String, Integer> PRIORITY_ORDER = Map.of("high", 0, "medium", 1, "low", 2);
    private static final Map<String, String> PRIORITY_LABEL = Map.of("high", "高优先级", "medium", "中优先级", "low", "低优先级");

    private final SkillRegistry registry;

    public ProductRecommendSkill(SkillRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "recommend_products",
                "当用户询问「推荐产品」「为客户推荐产品」「产品推荐」「产品智荐」「适合什么产品」「产品匹配」时调用此技能。" +
                        "根据企业信用代码或名称，返回匹配的金融产品推荐列表（按优先级排序）及详细分析H5链接。",
                this::handle,
                Map.of(
                        "credit_code", new Skill.SkillParam("string", "企业统一信用代码", false, "91110108MA01B3XK2P"),
                        "company_name", new Skill.SkillParam("string", "企业名称", false, "北京星河科技有限公司")
                )
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();

        Map<String, Object> allData = DataLoader.loadJson(PRODUCT_FILE);
        Map<String, Object> recommendations = DataLoader.getMap(allData, "recommendations");
        Map<String, Object> productPool = DataLoader.getMap(allData, "products");
        String baseUrl = DataLoader.buildBaseUrl();

        if (!creditCode.isEmpty()) {
            Map<String, Object> rec = DataLoader.getMap(recommendations, creditCode);
            if (!rec.isEmpty()) {
                return buildResponse(rec, productPool, baseUrl);
            }
            return notFound("未查询到信用代码 " + creditCode + " 的企业的产品推荐信息。");
        }

        if (!companyName.isEmpty()) {
            Map<String, String> nameIndex = (Map<String, String>) (Map<?, ?>) DataLoader.loadJson(NAME_INDEX_FILE);
            Map<String, Object> resolved = RiskCheckSkill.resolveCompanyMatch(companyName, nameIndex);

            if (resolved.containsKey("credit_code")) {
                String code = (String) resolved.get("credit_code");
                Map<String, Object> rec = DataLoader.getMap(recommendations, code);
                if (!rec.isEmpty()) {
                    return buildResponse(rec, productPool, baseUrl);
                }
                return notFound("已定位到企业，但暂无该企业的产品推荐。");
            }
            return resolved;
        }

        return notFound("请提供企业名称或统一信用代码进行产品推荐查询。");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildResponse(Map<String, Object> recData, Map<String, Object> productPool, String baseUrl) {
        String code = (String) recData.get("credit_code");
        List<Map<String, Object>> recs = (List<Map<String, Object>>) recData.getOrDefault("recommendations", List.of());

        List<Map<String, Object>> sorted = new ArrayList<>(recs);
        sorted.sort((a, b) -> {
            String pa = (String) a.getOrDefault("priority", "low");
            String pb = (String) b.getOrDefault("priority", "low");
            return Integer.compare(PRIORITY_ORDER.getOrDefault(pa, 99), PRIORITY_ORDER.getOrDefault(pb, 99));
        });

        List<Map<String, Object>> products = new ArrayList<>();
        for (Map<String, Object> r : sorted) {
            String key = (String) r.get("key");
            Map<String, Object> prod = DataLoader.getMap(productPool, key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("product_name", prod.getOrDefault("product_name", key));
            item.put("category", prod.getOrDefault("category", ""));
            item.put("priority", r.get("priority"));
            item.put("priority_label", PRIORITY_LABEL.getOrDefault(r.get("priority"), ""));
            item.put("reason", r.getOrDefault("reason", ""));
            item.put("expected_amount", r.getOrDefault("expected_amount", ""));
            item.put("features", prod.getOrDefault("features", List.of()));
            item.put("application_period", prod.getOrDefault("application_period", ""));
            products.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "result");
        result.put("credit_code", code);
        result.put("company_name", recData.get("company_name"));
        result.put("analysis_summary", recData.getOrDefault("analysis_summary", ""));
        result.put("products", products);
        result.put("detail_h5_url", baseUrl + "/h5/product-recommend.html?code=" + code);
        result.put("total_count", products.size());
        return result;
    }

    private Map<String, Object> notFound(String message) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "not_found");
        resp.put("message", message);
        return resp;
    }
}
