package com.IDDagent.skill;

import com.IDDagent.service.DDReportService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HistoricalDDQuerySkill {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDDQuerySkill.class);
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
                        "用户以查询行为词（查、查询、查一下、查看、看看、找）表达\"查XX报告\"、" +
                        "\"查XX的尽调报告\"、\"XX的报告\"（查询企业历史上生成的尽调报告）时同样调用此技能。" +
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
        log.info("HistoricalDDQuerySkill.handle: userId={}, companyName='{}', creditCode='{}', dateFrom='{}', dateTo='{}', userInput='{}'",
                userId, companyName, creditCode, dateFrom, dateTo,
                ((String) params.getOrDefault("_user_input", "")).trim());

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

        // 处理 _user_input（用户原始输入：pending 路径由 ChatController 注入，
        // Coordinator 路由路径由 CoordinatorService 注入，两条路径行为一致）
        // 注意：参数中的 company_name/credit_code 可能来自 Coordinator 的上下文自动补全
        // （复用上次查询的企业），并非用户本次明确提供，因此：
        // - 首次发起且用户输入清洗不出企业标识 → 清空企业参数走阶段一重新询问
        // - 用户已输入（_user_input 非空）→ 以用户输入为准覆盖企业参数（旧值作废）
        String userInput = ((String) params.getOrDefault("_user_input", "")).trim();
        // 用户输入中是否携带合法信用代码（选项卡点击会携带"公司名+信用代码"，或直接输入代码）
        // 携带即企业身份已确认，后续跳过模糊匹配直接查询（与自动补全的污染值区分开）
        boolean codeFromUser = false;
        if (!userInput.isEmpty()) {
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

            // 以用户最新输入为准更新企业信息（清洗掉查询前缀/后缀与时间描述）：
            // - 选项卡点击会携带"公司名+信用代码"（如"北京星河 91110108MA01B3XK2Q"），先提取代码
            // - 提取到代码（用户明确提供）→ 企业身份已确认，后续跳过模糊匹配直接查询
            // - 提取后剩余非空 → 作为 company_name 并清空旧 credit_code（含自动补全的旧值作废）
            // - 清洗后为空（如仅补充"近一月"时间）→ 保留原有企业信息
            String cleaned = userInput
                    .replaceAll("^(查询|查找|搜索|看一下|看看|帮我查|帮我找|找一下|查一下|查)\\s*", "")
                    .replaceAll("\\s*(的历史尽调报告|的历史报告|的尽调报告|的尽调|的报告|的记录)$", "")
                    // 纯泛查询功能词（未携带企业名/代码，如"历史尽调""查看历史"）：整体剔除，
                    // 避免被当作企业名称参与模糊匹配而误报未找到
                    .replaceAll("^(历史尽调|尽调记录|历史报告|历史查询|查看历史|尽调历史|以前的报告|查询历史|以往的尽调|查一下之前|查一下之前的尽调|历史尽调报告|尽调报告)$", "")
                    .replaceAll("(近|最近|过去)\\s*[0-9一二两三四五六七八九十]+\\s*个?月", "")
                    .replaceAll("(近|最近|过去)\\s*[0-9一二两三四五六七八九十]+\\s*年", "")
                    .replaceAll("半年|季度", "")
                    .replaceAll("\\d{4}-\\d{2}-\\d{2}", "")
                    .replaceAll("\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?", "")
                    .trim();
            // 提取信用代码（选项卡点击/直接输入代码场景）：
            // 1) 选项卡点击格式"公司：XXX\n统一信用代码：YYY"（前端按字段名发送）——
            //    直接解析出企业名称与代码，视为企业身份已确认
            // 2) 选项卡点击兼容格式"公司名 + 空格 + 代码"——识别尾部连续字母数字串（≥6 位）
            //    兼容 18 位标准统一社会信用代码与测试用的短数字占位代码
            // 3) 整串即为代码（直接手输信用代码）
            java.util.regex.Matcher codeField = java.util.regex.Pattern.compile("统一信用代码[:：]\\s*([0-9A-Za-z]+)").matcher(cleaned);
            if (codeField.find()) {
                creditCode = codeField.group(1).toUpperCase();
                codeFromUser = true;
                java.util.regex.Matcher nameField = java.util.regex.Pattern.compile("公司[:：]\\s*([^\\n\\r]+)").matcher(cleaned);
                if (nameField.find()) {
                    String nm = nameField.group(1).trim();
                    int sep = nm.indexOf("统一信用代码");
                    if (sep > 0) nm = nm.substring(0, sep).trim();
                    if (!nm.isEmpty()) companyName = nm;
                }
                cleaned = "";
            } else {
                java.util.regex.Matcher ccMatcher = java.util.regex.Pattern.compile("\\s+[0-9A-Za-z]{6,}\\s*$").matcher(cleaned);
                if (ccMatcher.find()) {
                    creditCode = ccMatcher.group().trim().toUpperCase();
                    codeFromUser = true;
                    cleaned = cleaned.substring(0, ccMatcher.start()).trim();
                } else if (cleaned.matches("[0-9A-Za-z]{6,}")) {
                    creditCode = cleaned.toUpperCase();
                    codeFromUser = true;
                    cleaned = "";
                }
            }
            if (!cleaned.isEmpty()) {
                companyName = cleaned;
                if (!codeFromUser) {
                    creditCode = "";
                }
            }
            // 用户本次输入未携带企业标识（如仅输入"历史尽调""查看历史"等纯功能词），
            // 也未提供时间区间 → 视为未指定企业，忽略 Coordinator/上下文自动补全注入的
            // 企业参数（否则会直接复用上次查询企业输出旧结果），走阶段一重新询问
            if (cleaned.isEmpty() && !codeFromUser && dateFrom.isEmpty() && dateTo.isEmpty()) {
                companyName = "";
                creditCode = "";
            }
        }

        // ============================================================
        // 阶段一：检查是否缺少企业名称/编号
        // ============================================================
        // 非法的 credit_code（如 LLM 误把公司名塞入该参数）视为未提供，避免查询层按代码过滤出错
        if (!isValidCreditCode(creditCode)) {
            creditCode = "";
        }
        if (creditCode.isEmpty() && companyName.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "info_needed");
            result.put("message", "请问您要查询哪家企业的历史尽调报告？可提供企业名称或统一信用代码。");
            return result;
        }

        // ============================================================
        // 阶段二：企业名称模糊匹配 —— 只要用户以公司名发起查询（无论精确/模糊、首次/追问）
        // 一律返回 candidates 选项卡供用户确认（可能存在同名不同信用代码的企业）；
        // 仅当用户输入携带信用代码（选项卡点击/直接输入代码）→ 企业身份已确认，跳过本阶段
        // ============================================================
        if (!companyName.isEmpty() && !codeFromUser) {
            Map<String, String> nameIndex = loadNameIndex(); // credit_code → company_name
            // 归一化：取包含在查询词中的最长索引名（"北京星河公司"→"北京星河"，避免被误缩成"星河"）
            // 若查询词本身就是索引中的精确企业名，则不做归一化
            if (!nameIndex.containsValue(companyName)) {
                String best = null;
                for (String idxName : nameIndex.values()) {
                    if (!idxName.equals(companyName) && companyName.contains(idxName)) {
                        if (best == null || idxName.length() > best.length()) {
                            best = idxName;
                        }
                    }
                }
                if (best != null) companyName = best;
            }

            List<Map<String, Object>> matches = RiskCheckSkill.fuzzyMatchCompany(companyName, nameIndex);

            // 扩展匹配集合：输入的公司名可能是其他企业的简称（如"星河"是"北京星河"的简称），
            // fuzzyMatchCompany 在精确命中时会直接短路返回单条，掩盖其他包含该名称的企业，
            // 因此将所有名称包含输入词的企业一并纳入选项卡（精确 100 分优先，包含 80 分次之）
            Map<String, Map<String, Object>> expanded = new LinkedHashMap<>();
            for (var idxEntry : nameIndex.entrySet()) {
                String idxName = idxEntry.getValue();
                if (idxName.contains(companyName)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("company_name", idxName);
                    m.put("credit_code", idxEntry.getKey());
                    m.put("_score", idxName.equals(companyName) ? 100 : 80);
                    expanded.putIfAbsent(idxEntry.getKey(), m);
                }
            }
            // 合并 fuzzyMatch 结果（覆盖名称不含输入词但相似的匹配，如错别字/子序列命中）
            for (Map<String, Object> m : matches) {
                expanded.putIfAbsent((String) m.getOrDefault("credit_code", ""), m);
            }
            matches = new ArrayList<>(expanded.values());
            matches.sort((a, b) -> ((Number) b.getOrDefault("_score", 0)).intValue()
                    - ((Number) a.getOrDefault("_score", 0)).intValue());

            if (matches.isEmpty()) {
                log.info("HistoricalDDQuerySkill 阶段二 not_found: companyName='{}', nameIndexSize={}",
                        companyName, nameIndex.size());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("action", "not_found");
                result.put("message", "未找到与「" + companyName + "」匹配的企业，请确认企业名称是否正确。");
                return result;
            }

            // 构造选项（含企业名称 + 统一社会信用代码）
            List<Map<String, Object>> options = new ArrayList<>();
            for (Map<String, Object> m : matches) {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("company_name", m.get("company_name"));
                opt.put("credit_code", m.getOrDefault("credit_code", ""));
                options.add(opt);
            }

            // 进入本阶段（用户以公司名发起、未携带信用代码）→ 一律展示选项卡让用户确认：
            // 可能存在同名不同信用代码的企业，必须由用户显式选择后才能查询；
            // 选项卡点击会携带"公司名+信用代码"，codeFromUser=true 已在阶段二入口拦截直接查询，不会死循环
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "candidates");
            result.put("keyword", companyName);
            
            result.put("options", options);
            return result;
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
        // 信用代码直接查询场景 companyName 为空：从查询结果中回填企业名称，
        // 保证结果卡片头部显示"企业：XXX"而非仅信用代码
        if (companyName.isEmpty() && !records.isEmpty()) {
            String recName = (String) records.get(0).getOrDefault("company_name", "");
            if (!recName.isEmpty()) companyName = recName;
        }
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
        // 从 DDReportService 获取 report.json 中的公司列表，构建 信用代码→公司名 索引
        Map<String, String> index = new LinkedHashMap<>();
        for (Map<String, Object> c : ddReportService.getAllCompanies()) {
            String name = (String) c.get("company_name");
            if (name == null || name.isEmpty()) continue;
            String cc = (String) c.getOrDefault("credit_code", "");
            index.put(cc.isEmpty() ? name : cc, name);
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
     * 判断字符串是否为合法的统一社会信用代码（18 位数字+大写字母）。
     * 用于识别 LLM 误把公司名塞进 credit_code 参数的情况（如 credit_code="星河"），
     * 此时仍应走企业名称模糊匹配出选项卡。
     */
    private static boolean isValidCreditCode(String code) {
        if (code == null || code.isEmpty()) return false;
        return code.toUpperCase().matches("[0-9A-Z]{18}");
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
