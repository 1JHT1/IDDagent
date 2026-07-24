package com.IDDagent.skill;

import com.IDDagent.service.DDReportService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HistoricalDDQuerySkill {

    private static final String NAME_INDEX_FILE = "data/company_name_index.json";
    private static final String NAME_INDEX_FALLBACK = "data-template/company_name_index.json";

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
                                "企业名称（必填，支持模糊输入）", false, "北京星河科技有限公司"),
                        "credit_code", new Skill.SkillParam("string",
                                "企业统一信用代码（必填，与企业名称至少提供一个）", false, "91110108MA01B3XK2P"),
                        "date_from", new Skill.SkillParam("string",
                                "尽调开始日期（可选，格式 yyyy-MM-dd，也支持\"近一个月\"等灵活描述；不提供则默认近三个月）", false, "2025-01-01"),
                        "date_to", new Skill.SkillParam("string",
                                "尽调结束日期（可选，格式 yyyy-MM-dd，不提供则默认当前时间）", false, "2025-12-31"),
                        "id_type", new Skill.SkillParam("string",
                                "证件类型（可选）", false, "身份证"),
                        "id_number", new Skill.SkillParam("string",
                                "证件号码（可选）", false, "4403****1234")
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
            // 从 _user_input 中提取日期/时间区间
            if (dateFrom.isEmpty() && dateTo.isEmpty()) {
                // 1. 先尝试解析灵活时间描述（如"近一个月"）
                java.time.LocalDate now = java.time.LocalDate.now();
                if (userInput.contains("近一个月") || userInput.contains("一个月内") || userInput.contains("近1个月")) {
                    dateFrom = now.minusMonths(1).toString();
                    dateTo = now.toString();
                } else if (userInput.contains("近三个月") || userInput.contains("三个月内") || userInput.contains("近3个月") || userInput.contains("近一季度")) {
                    dateFrom = now.minusMonths(3).toString();
                    dateTo = now.toString();
                } else if (userInput.contains("近半年") || userInput.contains("半年内") || userInput.contains("六个月内") || userInput.contains("近6个月")) {
                    dateFrom = now.minusMonths(6).toString();
                    dateTo = now.toString();
                } else if (userInput.contains("近一年") || userInput.contains("一年内") || userInput.contains("近1年")) {
                    dateFrom = now.minusMonths(12).toString();
                    dateTo = now.toString();
                } else {
                    // 2. 尝试匹配 "2024-01-01" 格式的标准日期
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
        // 阶段三：有企业信息但缺少时间区间 — 自动生成近三个月
        // ============================================================
        if (dateFrom.isEmpty() && dateTo.isEmpty()) {
            java.time.LocalDate now = java.time.LocalDate.now();
            dateFrom = now.minusMonths(3).toString();
            dateTo = now.toString();
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
        // 先尝试 data/ 目录，再尝试 data-template/ 目录
        Map<String, Object> data = DataLoader.loadJson(NAME_INDEX_FILE);
        if (data.isEmpty()) {
            data = DataLoader.loadJson(NAME_INDEX_FALLBACK);
        }
        return (Map<String, String>) (Map<?, ?>) data;
    }
}
