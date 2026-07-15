package com.cropagent.skill;

import com.cropagent.config.AppConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProductMatchSkill {

    private static final Logger log = LoggerFactory.getLogger(ProductMatchSkill.class);
    private static final String PRODUCT_FILE = "data/product_recommendations.json";
    private static final String NAME_INDEX_FILE = "data/company_name_index.json";
    private static final String OUTREACH_FILE = "data/customer_outreach.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SkillRegistry registry;
    private final WebClient webClient;
    private final AppConfig config;

    // Chinese number mapping
    private static final Map<String, Integer> CN_NUM_MAP = Map.ofEntries(
            Map.entry("一", 1), Map.entry("二", 2), Map.entry("三", 3), Map.entry("四", 4),
            Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8),
            Map.entry("九", 9), Map.entry("十", 10), Map.entry("两", 2));

    // Amount patterns (unit: 万元)
    private static final List<Pattern> AMOUNT_PATTERNS = List.of(
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*亿"),
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*千万"),
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*百万"),
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*万"));

    private static final double[] AMOUNT_MULTIPLIERS = {10000, 1000, 100, 1};

    private static final List<String> INVESTMENT_KEYWORDS = List.of(
            "入账", "到账", "收到", "回款", "闲置", "停留", "暂存", "暂放",
            "闲置资金", "收益最高", "收益最大化", "理财", "保值", "增值", "存款", "存单", "短期理财", "资金管理");
    private static final List<String> BORROWING_KEYWORDS = List.of(
            "贷款", "借款", "融资", "信贷", "授信", "借钱", "资金不足",
            "资金缺口", "需要资金", "周转困难", "资金需求", "贷款额度");
    private static final Set<String> INVESTMENT_CATEGORIES = Set.of("对公理财", "对公存款", "现金管理");
    private static final Set<String> LENDING_CATEGORIES = Set.of("对公信贷", "融资租赁", "供应链金融");

    private static final String MATCH_SYSTEM_PROMPT = """
            你是一个银行金融产品智能匹配顾问。根据客户的具体资金需求，从产品货架中挑选最合适的产品，并给出推荐理由和产品亮点。

            ## 任务
            1. 根据客户需求和画像，从产品货架中筛选最匹配的产品（最多3个）
            2. 为每个匹配产品给出匹配度评分（0-100）和推荐理由
            3. 计算预估收益（如适用）
            4. 提取产品亮点（3-5个关键点）

            ## 输出格式（严格 JSON，不要包裹代码块）
            {
              "needs_summary": "一句话总结客户需求",
              "matches": [
                {
                  "product_key": "产品key",
                  "match_score": 95,
                  "reason": "推荐理由（2-3句话，说明为什么匹配）",
                  "highlights": ["亮点1", "亮点2", "亮点3"],
                  "estimated_return": "预估收益（如适用，否则留空）"
                }
              ]
            }

            ## 评分标准
            - 90-100: 完美匹配（金额、期限、风险、流动性全部契合）
            - 75-89: 高度匹配（大部分条件契合）
            - 60-74: 部分匹配（可推荐但有一定限制）
            - <60: 不推荐

            ## 注意事项
            - 只输出 JSON，不要输出其他文本
            - 按 match_score 降序排列
            - 推荐理由要具体、有数据支撑
            - 预估收益要基于实际利率计算
            - 产品货架已根据客户资金方向预筛选""";

    public ProductMatchSkill(SkillRegistry registry, WebClient webClient, AppConfig config) {
        this.registry = registry;
        this.webClient = webClient;
        this.config = config;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "match_products_intelligently",
                "当用户描述了具体的资金需求场景时调用此技能，例如" +
                        "「客户有5千万工程款闲置一个月」「需要短期理财」「有一笔大额资金需要灵活管理」等。" +
                        "基于用户需求从产品货架中智能匹配最佳产品，给出推荐理由和产品亮点。" +
                        "注意：当用户仅说「为XX企业推荐产品」时，应使用 recommend_products 技能而非此技能。",
                this::handle,
                Map.of(
                        "query", new Skill.SkillParam("string", "用户需求描述文本", true, "客户最近有一笔5千万的工程款打入"),
                        "company_name", new Skill.SkillParam("string", "企业名称（可选）", false, "北京星河科技有限公司"),
                        "credit_code", new Skill.SkillParam("string", "企业统一信用代码（可选）", false, "91110108MA01B3XK2P")
                )
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String query = ((String) params.getOrDefault("query", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();

        if (query.isEmpty()) {
            return error("请描述您的资金需求，我将为您智能匹配最合适的金融产品。");
        }

        // 1. Extract structured needs
        Map<String, Object> needs = extractNeeds(query);

        // 2. Load product shelf
        Map<String, Object> allData = DataLoader.loadJson(PRODUCT_FILE);
        Map<String, Object> products = DataLoader.getMap(allData, "products");
        if (products.isEmpty()) {
            return error("产品货架暂无数据，请联系管理员。");
        }

        // 2.5 Filter by fund direction
        String fundDirection = (String) needs.getOrDefault("fund_direction", "both");
        Map<String, Object> filteredProducts = filterByDirection(products, fundDirection);
        if (filteredProducts.isEmpty()) filteredProducts = products;
        log.info("Fund direction: {}, products: {} -> {}", fundDirection, products.size(), filteredProducts.size());

        // 3. Load company profile
        Map<String, Object> companyProfile = null;
        if (!companyName.isEmpty() && creditCode.isEmpty()) {
            Map<String, String> nameIndex = (Map<String, String>) (Map<?, ?>) DataLoader.loadJson(NAME_INDEX_FILE);
            Map<String, Object> resolved = RiskCheckSkill.resolveCompanyMatch(companyName, nameIndex);
            if (resolved.containsKey("credit_code")) {
                creditCode = (String) resolved.get("credit_code");
                companyName = (String) resolved.getOrDefault("company_name", companyName);
            }
        }
        if (!creditCode.isEmpty() || !companyName.isEmpty()) {
            companyProfile = loadCompanyProfile(creditCode, companyName);
        }

        // 4. LLM matching (blocking call - since skill handler is synchronous)
        Map<String, Object> llmResult;
        try {
            llmResult = callLlmMatch(needs, filteredProducts, companyProfile);
        } catch (Exception e) {
            log.error("LLM matching failed: {}", e.getMessage());
            return error("产品智能匹配暂时不可用，请稍后重试。");
        }

        // 5. Enrich results
        String needsSummary = (String) llmResult.getOrDefault("needs_summary", "");
        List<Map<String, Object>> matches = (List<Map<String, Object>>) llmResult.getOrDefault("matches", List.of());
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> m : matches) {
            String key = (String) m.get("product_key");
            Map<String, Object> prod = DataLoader.getMap(products, key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("product_key", key);
            item.put("product_name", prod.getOrDefault("product_name", key));
            item.put("category", prod.getOrDefault("category", ""));
            item.put("match_score", m.getOrDefault("match_score", 0));
            item.put("reason", m.getOrDefault("reason", ""));
            item.put("highlights", m.getOrDefault("highlights", List.of()));
            item.put("estimated_return", m.getOrDefault("estimated_return", ""));
            item.put("features", prod.getOrDefault("features", List.of()));
            item.put("application_period", prod.getOrDefault("application_period", ""));
            enriched.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "result");
        result.put("needs_summary", needsSummary);
        result.put("needs_detail", needs);
        result.put("matches", enriched);
        result.put("company_name", companyProfile != null ? companyProfile.get("company_name") : null);
        result.put("credit_code", creditCode.isEmpty() ? null : creditCode);
        result.put("total_count", enriched.size());
        return result;
    }

    // === Needs Extraction ===

    private Map<String, Object> extractNeeds(String text) {
        List<Double> amounts = extractAmounts(text);
        Integer duration = extractDuration(text);
        String riskPref = extractRiskPreference(text);
        boolean returnPriority = text.contains("收益最高") || text.contains("收益最大化")
                || text.contains("最高收益") || text.contains("收益优先") || text.contains("回报最高")
                || text.contains("赚更多") || text.contains("增值");
        String liquidity = extractLiquidity(text);
        String purpose = extractPurpose(text);

        Double totalAmount = amounts.isEmpty() ? null : amounts.get(0);
        Double availableAmount = amounts.size() > 1 ? amounts.get(1) : (amounts.size() == 1 ? amounts.get(0) : null);

        Map<String, Object> needs = new LinkedHashMap<>();
        needs.put("total_amount", totalAmount);
        needs.put("available_amount", availableAmount);
        needs.put("duration_days", duration);
        needs.put("purpose", purpose);
        needs.put("risk_preference", riskPref);
        needs.put("liquidity_need", liquidity);
        needs.put("return_priority", returnPriority);
        needs.put("fund_direction", classifyFundDirection(text));
        return needs;
    }

    private List<Double> extractAmounts(String text) {
        Set<Double> amounts = new TreeSet<>(Comparator.reverseOrder());
        for (int i = 0; i < AMOUNT_PATTERNS.size(); i++) {
            Matcher m = AMOUNT_PATTERNS.get(i).matcher(text);
            while (m.find()) {
                double val = Double.parseDouble(m.group(1)) * AMOUNT_MULTIPLIERS[i];
                if (val > 0) amounts.add(val);
            }
        }
        // Chinese number patterns
        for (var entry : CN_NUM_MAP.entrySet()) {
            if (text.contains(entry.getKey() + "亿")) {
                amounts.add((double) entry.getValue() * 10000);
            } else if (text.contains(entry.getKey() + "千万")) {
                amounts.add((double) entry.getValue() * 1000);
            } else if (text.contains(entry.getKey() + "万")) {
                amounts.add((double) entry.getValue());
            }
        }
        return new ArrayList<>(amounts);
    }

    private Integer extractDuration(String text) {
        // Half year
        if (text.contains("半年")) return 180;
        // Half month
        if (text.contains("半个月")) return 15;

        Object[][] patterns = {
                {Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*年"), 365.0},
                {Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*个?月"), 30.0},
                {Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*周"), 7.0},
                {Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[天日]"), 1.0},
        };
        for (Object[] p : patterns) {
            Matcher m = ((Pattern) p[0]).matcher(text);
            if (m.find()) return (int) (Double.parseDouble(m.group(1)) * (Double) p[1]);
        }
        // Chinese numbers
        for (var entry : CN_NUM_MAP.entrySet()) {
            String cn = entry.getKey();
            if (text.contains(cn + "年")) return entry.getValue() * 365;
            if (text.contains(cn + "个月")) return entry.getValue() * 30;
            if (text.contains(cn + "周")) return entry.getValue() * 7;
        }
        return null;
    }

    private String extractRiskPreference(String text) {
        if (text.contains("保本") || text.contains("无风险") || text.contains("安全")
                || text.contains("稳健") || text.contains("零风险") || text.contains("低风险")
                || text.contains("保息") || text.contains("存款保险")) return "low";
        if (text.contains("稳健增值") || text.contains("平衡") || text.contains("适度风险")
                || text.contains("中等风险")) return "medium";
        if (text.contains("高收益") || text.contains("激进") || text.contains("高风险")
                || text.contains("进取")) return "high";
        return "medium";
    }

    private String extractLiquidity(String text) {
        for (String kw : List.of("随时取出", "随时支取", "灵活支取", "随时赎回", "活期", "T+0", "即时", "灵活管理", "随时")) {
            if (text.contains(kw)) return "high";
        }
        for (String kw : List.of("停留", "闲置", "暂存")) {
            if (text.contains(kw)) return "medium";
        }
        return "medium";
    }

    private String extractPurpose(String text) {
        String[] purposes = {"购买原材料", "采购", "备货", "设备采购", "设备更新", "扩建", "扩张",
                "工程", "工程款", "项目款", "投资", "并购", "收购", "日常经营",
                "发工资", "缴税", "偿还贷款", "还贷", "支付货款"};
        for (String p : purposes) {
            if (text.contains(p)) return p;
        }
        return null;
    }

    private String classifyFundDirection(String text) {
        boolean hasInvest = INVESTMENT_KEYWORDS.stream().anyMatch(text::contains);
        boolean hasBorrow = BORROWING_KEYWORDS.stream().anyMatch(text::contains);
        if (hasInvest && !hasBorrow) return "investment";
        if (hasBorrow && !hasInvest) return "borrowing";
        return "both";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterByDirection(Map<String, Object> products, String direction) {
        if ("both".equals(direction)) return products;
        Set<String> excludeCategories = "investment".equals(direction) ? LENDING_CATEGORIES : INVESTMENT_CATEGORIES;
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (var entry : products.entrySet()) {
            Map<String, Object> prod = (Map<String, Object>) entry.getValue();
            String category = (String) prod.getOrDefault("category", "");
            if (!excludeCategories.contains(category)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadCompanyProfile(String creditCode, String companyName) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("company_name", companyName);
        profile.put("credit_code", creditCode);

        if (!creditCode.isEmpty()) {
            Map<String, Object> outreach = DataLoader.loadJson(OUTREACH_FILE);
            Map<String, Object> data = (Map<String, Object>) outreach.get(creditCode);
            if (data != null) {
                profile.put("company_name", data.getOrDefault("company_name", companyName));
                profile.put("insights", data.get("insights"));
            }
        }
        return profile;
    }

    private Map<String, Object> callLlmMatch(Map<String, Object> needs, Map<String, Object> products,
                                              Map<String, Object> companyProfile) {
        String apiKey = config.getDeepseek().getApiKey();
        String baseUrl = config.getDeepseek().getBaseUrl();
        String model = config.getModel().getCoordinator();

        String userPrompt = buildMatchPrompt(needs, products, companyProfile);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", MATCH_SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt)));
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", 1500);

        try {
            String response = webClient.post()
                    .uri(baseUrl + "/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Map<String, Object> respMap = mapper.readValue(response, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                String content = (String) message.get("content");
                if (content != null) {
                    return extractJson(content);
                }
            }
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("needs_summary", "需求解析完成，但产品匹配结果返回异常");
        fallback.put("matches", List.of());
        return fallback;
    }

    private String buildMatchPrompt(Map<String, Object> needs, Map<String, Object> products,
                                     Map<String, Object> companyProfile) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 客户需求\n");
        if (needs.get("total_amount") != null) sb.append("- 总金额: ").append(needs.get("total_amount")).append("万元\n");
        if (needs.get("available_amount") != null) sb.append("- 可用金额: ").append(needs.get("available_amount")).append("万元\n");
        if (needs.get("duration_days") != null) sb.append("- 资金停留期限: ").append(needs.get("duration_days")).append("天\n");
        if (needs.get("purpose") != null) sb.append("- 资金用途: ").append(needs.get("purpose")).append("\n");
        sb.append("- 风险偏好: ").append(needs.get("risk_preference")).append("\n");
        sb.append("- 流动性需求: ").append(needs.get("liquidity_need")).append("\n");
        sb.append("- 收益优先: ").append(Boolean.TRUE.equals(needs.get("return_priority")) ? "是" : "否").append("\n");

        if (companyProfile != null && companyProfile.get("company_name") != null) {
            sb.append("\n## 企业画像\n");
            sb.append("- 企业名称: ").append(companyProfile.get("company_name")).append("\n");
            @SuppressWarnings("unchecked")
            Map<String, Object> insights = (Map<String, Object>) companyProfile.get("insights");
            if (insights != null) {
                Object profileText = insights.get("company_profile");
                if (profileText != null) sb.append("- 企业概况: ").append(profileText).append("\n");
            }
        }

        sb.append("\n## 产品货架\n");
        for (var entry : products.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> prod = (Map<String, Object>) entry.getValue();
            sb.append("- **").append(entry.getKey()).append("** (")
                    .append(prod.get("product_name")).append("): ")
                    .append(prod.get("category")).append(", 风险")
                    .append(prod.get("risk_level")).append(", 流动性")
                    .append(prod.get("liquidity"));
            if (prod.get("min_term_days") != null) {
                sb.append(", 期限").append(prod.get("min_term_days")).append("-").append(prod.get("max_term_days")).append("天");
            }
            if (prod.get("min_amount") != null) {
                String maxStr = prod.get("max_amount") != null ? "-" + prod.get("max_amount") : "+";
                sb.append(", 金额").append(prod.get("min_amount")).append(maxStr).append("万");
            }
            sb.append("\n");
            @SuppressWarnings("unchecked")
            List<String> features = (List<String>) prod.getOrDefault("features", List.of());
            sb.append("  特点: ").append(String.join("; ", features)).append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return mapper.readValue(text.substring(start, end + 1), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("JSON parse failed: {}", e.getMessage());
            }
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("needs_summary", "需求解析完成，但产品匹配结果返回异常");
        fallback.put("matches", List.of());
        return fallback;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("error", message);
        return resp;
    }
}
