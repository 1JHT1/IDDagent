package com.IDDagent.skill;

import com.IDDagent.service.DDReportService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HistoricalDDQuerySkill {

    private final SkillRegistry registry;
    private final DDReportService ddReportService;

    public HistoricalDDQuerySkill(SkillRegistry registry, DDReportService ddReportService) {
        this.registry = registry;
        this.ddReportService = ddReportService;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "query_due_diligence_reports",
                "当用户需要查询历史尽调报告、尽调记录、历史报告、查一下之前、以往的尽调、历史查询、" +
                        "查看历史、尽调历史、以前的报告时调用此技能。" +
                        "根据企业名称或统一信用代码以及尽调申请时间区间查询历史尽调报告，" +
                        "返回报告列表（含查看详情、编辑、下载、附件操作）。",
                this::handle,
                Map.of(
                        "company_name", new Skill.SkillParam("string",
                                "企业名称（必填，支持模糊输入）", false, ""),
                        "credit_code", new Skill.SkillParam("string",
                                "企业统一信用代码（必填，与企业名称至少提供一个）", false, ""),
                        "date_from", new Skill.SkillParam("string",
                                "尽调开始日期（可选，格式 yyyy-MM-dd，也支持\"近一个月\"等灵活描述；不提供则默认近三个月）", false, ""),
                        "date_to", new Skill.SkillParam("string",
                                "尽调结束日期（可选，格式 yyyy-MM-dd，不提供则默认当前时间）", false, ""),
                        "id_type", new Skill.SkillParam("string",
                                "证件类型（可选）", false, ""),
                        "id_number", new Skill.SkillParam("string",
                                "证件号码（可选）", false, "")
                )
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();
        String dateFrom = ((String) params.getOrDefault("date_from", "")).trim();
        String dateTo = ((String) params.getOrDefault("date_to", "")).trim();
        String idType = ((String) params.getOrDefault("id_type", "")).trim();
        String idNumber = ((String) params.getOrDefault("id_number", "")).trim();

        // 规范化 LLM 可能直接传入的相对时间描述（如 date_from="近一个月"）
        {
            java.time.LocalDate now = java.time.LocalDate.now();
            Integer months = matchRelativeMonths(dateFrom + " " + dateTo);
            if (months != null) {
                dateFrom = now.minusMonths(months).toString();
                dateTo = now.toString();
            } else {
                // 非 yyyy-MM-dd 格式的值直接清空，避免下游解析失败
                if (!dateFrom.isEmpty() && !dateFrom.matches("\\d{4}-\\d{2}-\\d{2}")) dateFrom = "";
                if (!dateTo.isEmpty() && !dateTo.matches("\\d{4}-\\d{2}-\\d{2}")) dateTo = "";
            }
        }

        // 处理 _user_input（来自待处理技能的下一条用户消息）
        String userInput = ((String) params.getOrDefault("_user_input", "")).trim();
        if (!userInput.isEmpty()) {
            // 如果尚未确定信用代码（企业未精确匹配），始终使用 _user_input 作为最新企业名输入
            // 这确保用户点击候选后，选中的精确企业名能替代上一轮的模糊 keyword
            if (creditCode.isEmpty()) {
                String cleaned = userInput
                        .replaceAll("^(查询|查找|搜索|看一下|看看|帮我查|帮我找|找一下|查一下|查)\\s*", "")
                        .replaceAll("\\s*(的历史尽调报告|的历史报告|的尽调报告|的尽调|的报告|的记录)$", "")
                        .trim();
                companyName = cleaned.isEmpty() ? userInput : cleaned;
            }
            // 从 _user_input 中提取日期/时间区间（优先于 LLM 传入的日期参数）
            // 因为 LLM 不知道当前实际时间，计算相对时间（如"近一年"）会出错
            java.time.LocalDate now = java.time.LocalDate.now();
            Integer months = matchRelativeMonths(userInput);
            if (months != null) {
                dateFrom = now.minusMonths(months).toString();
                dateTo = now.toString();
            } else if (dateFrom.isEmpty() && dateTo.isEmpty()) {
                // 无时间关键词且 LLM 未传日期时，尝试解析 _user_input 中的显式日期
                    java.util.regex.Matcher dateMatcher = java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2})")
                            .matcher(userInput);
                    List<String> dates = new ArrayList<>();
                    while (dateMatcher.find()) {
                        dates.add(dateMatcher.group(1));
                    }
                    // 再尝试匹配 "2024年1月1日" 中文格式
                    if (dates.size() < 2) {
                        java.util.regex.Matcher cnMatcher = java.util.regex.Pattern.compile(
                                "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})?\\s*日?").matcher(userInput);
                        dates.clear();
                        while (cnMatcher.find()) {
                            String y = cnMatcher.group(1);
                            String m = String.format("%02d", Integer.parseInt(cnMatcher.group(2)));
                            String d = cnMatcher.group(3) != null ? String.format("%02d", Integer.parseInt(cnMatcher.group(3))) : "01";
                            dates.add(y + "-" + m + "-" + d);
                        }
                    }
                    if (dates.size() >= 2) {
                        dateFrom = dates.get(0);
                        dateTo = dates.get(dates.size() - 1);
                    } else if (dates.size() == 1) {
                        dateFrom = dates.get(0);
                        dateTo = dates.get(0);
                    }
                }
            }

        // ============================================================
        // 阶段一：检查是否缺少企业名称/编号
        // ============================================================
        if (creditCode.isEmpty() && companyName.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "info_needed");
            result.put("message", "请问您要查询哪家企业的历史尽调报告？可提供企业名称或统一信用代码。");
            return result;
        }

        // ============================================================
        // 阶段二：如果有企业名称但无信用代码，进行模糊匹配
        // ============================================================
        if (!companyName.isEmpty() && creditCode.isEmpty()) {
            Map<String, String> nameIndex = loadNameIndex();
            // 归一化：若传入的是带后缀/全称（如"星河公司""北京星河科技有限公司"），
            // 先反向匹配到 report.json 中的简称（"星河"），再进行精确匹配
            for (String idxName : nameIndex.values()) {
                if (!idxName.equals(companyName) && companyName.contains(idxName)) {
                    companyName = idxName;
                    break;
                }
            }
            Map<String, Object> resolved = RiskCheckSkill.resolveCompanyMatch(companyName, nameIndex);

            // 精确匹配到唯一企业
            if (resolved.containsKey("credit_code")) {
                creditCode = (String) resolved.get("credit_code");
                // 从 nameIndex 获取标准企业名称
                companyName = nameIndex.getOrDefault(creditCode, companyName);
            }
            // 存在歧义/未找到，返回候选列表供用户选择
            else if (resolved.containsKey("action")) {
                String action = (String) resolved.get("action");
                if ("ambiguous".equals(action) || "not_found".equals(action)) {
                    // 将 resolveCompanyMatch 的结果转为 candidates 格式
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("action", "candidates");
                    result.put("keyword", resolved.getOrDefault("keyword", companyName));
                    result.put("message", resolved.getOrDefault("message", "请选择要查询的企业："));
                    result.put("options", resolved.getOrDefault("options", List.of()));
                    return result;
                }
            }
        }

        // ============================================================
        // 阶段三：有企业信息但缺少时间区间 — 自动生成近三个月（仅设起始，无上界）
        // ============================================================
        if (dateFrom.isEmpty() && dateTo.isEmpty()) {
            java.time.LocalDate now = java.time.LocalDate.now();
            dateFrom = now.minusMonths(3).toString();
            // dateTo 留空，DDReportService 中无上界则不限制
        }

        // ============================================================
        // 阶段四：参数完整，执行查询
        // ============================================================
        List<Map<String, Object>> records = ddReportService.queryReports(
                creditCode, companyName, dateFrom, dateTo, userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_skill_name", "query_due_diligence_reports");

        if (records.isEmpty()) {
            result.put("action", "not_found");
            String nameDisplay = !companyName.isEmpty() ? companyName : creditCode;
            result.put("message", "未查询到「" + nameDisplay + "」在指定时间区间内的历史尽调报告。");
            result.put("company_name", companyName);
            result.put("credit_code", creditCode);
            result.put("query_params", Map.of(
                    "date_from", dateFrom,
                    "date_to", dateTo
            ));
            return result;
        }

        result.put("action", "result");
        result.put("company_name", companyName);
        result.put("credit_code", creditCode);
        result.put("total_count", records.size());
        result.put("query_params", Map.of(
                "date_from", dateFrom,
                "date_to", dateTo
        ));
        result.put("records", records);

        // 证件类型/号码作为额外的过滤条件信息（当前查询暂未使用，保留以便未来扩展）
        if (!idType.isEmpty()) {
            result.put("id_type", idType);
        }
        if (!idNumber.isEmpty()) {
            result.put("id_number", idNumber);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadNameIndex() {
        // 从 DDReportService 获取 report.json 中的公司名列表，构建名称索引
        // 由于 report.json 中无 credit_code，使用公司名自身作为 key
        Map<String, String> index = new LinkedHashMap<>();
        List<String> names = ddReportService.getAllCompanyNames();
        for (String name : names) {
            index.put(name, name);
        }
        // 如果 report.json 中无数据，回退到旧文件名索引
        if (index.isEmpty()) {
            Map<String, Object> fallback = DataLoader.loadJson("data-template/company_name_index.json");
            if (!fallback.isEmpty()) {
                return (Map<String, String>) (Map<?, ?>) fallback;
            }
        }
        return index;
    }

    /**
     * 从文本中识别相对时间描述，返回对应的"往前推的月数"。
     * 无法识别时返回 null。
     */
    private Integer matchRelativeMonths(String text) {
        if (text == null || text.isEmpty()) return null;
        // 特例：半年 / 季度
        if (text.contains("半年")) return 6;
        if (text.contains("季度")) return 3;
        // 近/最近/过去 + N + 年（相对年数，需前缀以避免误匹配"2024年"绝对日期）
        java.util.regex.Matcher ym = java.util.regex.Pattern
                .compile("(?:近|最近|过去)\\s*([0-9]+|[一二两三四五六七八九十]+)\\s*年").matcher(text);
        if (ym.find()) {
            Integer n = parseCnNumber(ym.group(1));
            if (n != null) return n * 12;
        }
        // "N年内" 形式（如"一年内"）
        java.util.regex.Matcher ymIn = java.util.regex.Pattern
                .compile("([0-9]+|[一二两三四五六七八九十]+)\\s*年内").matcher(text);
        if (ymIn.find()) {
            Integer n = parseCnNumber(ymIn.group(1));
            if (n != null) return n * 12;
        }
        // 近/最近/过去 + N + (个)月
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("(?:近|最近|过去)\\s*([0-9]+|[一二两三四五六七八九十]+)\\s*个?月").matcher(text);
        if (mm.find()) {
            Integer n = parseCnNumber(mm.group(1));
            if (n != null) return n;
        }
        // "N(个)月内" 形式（如"三个月内"）
        java.util.regex.Matcher mmIn = java.util.regex.Pattern
                .compile("([0-9]+|[一二两三四五六七八九十]+)\\s*个?月内").matcher(text);
        if (mmIn.find()) {
            Integer n = parseCnNumber(mmIn.group(1));
            if (n != null) return n;
        }
        return null;
    }

    /**
     * 将阿拉伯数字或中文数字（1~99，含"两"）转为整数。无法识别返回 null。
     */
    private Integer parseCnNumber(String s) {
        if (s == null || s.isEmpty()) return null;
        if (s.matches("[0-9]+")) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
        }
        Map<Character, Integer> d = new HashMap<>();
        d.put('一', 1); d.put('二', 2); d.put('两', 2); d.put('三', 3); d.put('四', 4);
        d.put('五', 5); d.put('六', 6); d.put('七', 7); d.put('八', 8); d.put('九', 9);
        if (s.equals("十")) return 10;
        if (s.length() == 1) return d.get(s.charAt(0));
        if (s.startsWith("十")) {           // 十一 ~ 十九
            Integer u = d.get(s.charAt(1));
            return u == null ? null : 10 + u;
        }
        if (s.endsWith("十")) {             // 二十、三十...
            Integer t = d.get(s.charAt(0));
            return t == null ? null : t * 10;
        }
        if (s.length() == 3 && s.charAt(1) == '十') { // 二十一...
            Integer t = d.get(s.charAt(0));
            Integer u = d.get(s.charAt(2));
            return (t == null || u == null) ? null : t * 10 + u;
        }
        return null;
    }
}
