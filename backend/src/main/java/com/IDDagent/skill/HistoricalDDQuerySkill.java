package com.IDDagent.skill;

import com.IDDagent.service.CompanyNameExtractor;
import com.IDDagent.service.DDReportService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class HistoricalDDQuerySkill {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDDQuerySkill.class);

    /**
     * 从用户原始输入中提取"查询行为词 + 企业名 + 报告类词"结构的企业名称
     * （如"查小米科技的报告" → "小米科技"、"查一下小米科技的尽调报告" → "小米科技"）。
     * 组 1 非贪婪：遇到"的/历史/尽调/报告"结构即停止，避免吞入模板词。
     */
    private static final Pattern QUERY_COMPANY_PATTERN = Pattern.compile(
            "(?:查一下|查下|帮我查下|帮我查询|帮我查|查询|查看|看看|找一下|搜索|我想查|我要查|查)" +
            "\\s*([^，。；、\\s]{2,30}?)\\s*(?:的)?(?:历史)?(?:尽调)?(?:报告|记录)");

    /**
     * 疑问/问题句式判定：含典型疑问词（什么/怎么/是否/有没有/吗/呢 等）或提问类名词
     * （介绍/定义/含义/意思/包含/包括）。用户以问题句式表达时（如"历史尽调报告怎么查"、
     * "查什么报告"）是在提问而非提供企业名——兜底提取必须放弃把残渣当主体，否则会把
     * 问题文本直接当公司名去查询/误报"未查询到报告"，应先询问主体。
     * 真实企业名不含疑问词，不受影响。
     * 与 IntentPlannerService/RiskCheckSkill/InformationCheckSkill 的 QUESTION_PATTERN 同源。
     */
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "什么|哪些|哪个|怎么|如何|为什么|为啥|多少|有没有|是否|是不是|嘛|呢|吗|啥|干嘛|干什么|做什么|介绍|定义|含义|意思|包含|包括");

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
        ), List.of("历史尽调", "查询历史", "尽调记录", "以前的报告", "查下历史记录", "之前查过吗"));
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
        // 仅从中提取日期/时间区间；企业名称/信用代码以上层解析结果（参数）为准
        String userInput = ((String) params.getOrDefault("_user_input", "")).trim();
        // 多意图规划标记（buildPlan 注入）：多意图句子（如"查下风险和历史尽调报告"）中 LLM 可能把
        // 功能词残渣（"风险识别""历史尽调报告"）填进 company_name，_user_input 兜底提取也可能把
        // 多意图句子的残余当公司名——都不是用户提供的企业主体。标记生效时：无主体 → 跳过下方
        // _user_input 兜底提取（多意图句子本身不是企业名），直接询问主体；有主体 → 由参数清洗
        // 结合 isFunctionalResidue 判定清洗功能词残渣（清洗后为空/残渣 → 询问主体）。
        // 日期/时间区间提取不受此标记影响（"查小米近一年的尽调报告"也要解析时间）。
        boolean fromMultiIntent = Boolean.TRUE.equals(params.get("_from_multi_intent"));

        // 共享广播主体标记（broadcastParams 写入）：主体来自用户对"请提供企业名称"询问的显式回答
        // 或技能解析（info_needed 响应），由规划层广播到后续缺失主体的步骤。广播主体虽不在该步骤
        // 自己的 _user_input 中（仍是 buildPlan 注入的原始多意图句子），但可信度等同用户输入——
        // 主体可信度校验必须跳过它，否则会被清空再次询问主体，形成多意图流程中"回答后一直循环"。
        boolean broadcastSubject = Boolean.TRUE.equals(params.get("_broadcast_subject"));
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


        }

        // ============================================================
        // 阶段一（前置）：从 _user_input 兜底提取企业标识
        // LLM 意图识别可能只路由技能而未将企业名填入 company_name 参数（prompt 规则仅强调
        // 匹配技能，未强制提取参数），或 pending 路径（info_needed 后用户补充企业名，如
        // 回复"小米科技"）中参数仍为空——此时企业名称只存在于用户原始输入中，必须兜底提取，
        // 否则会误报"请问您要查询哪家企业"而忽略用户已提供的信息。
        // 无合法信用代码时，company_name 若不在用户本次输入中出现（上层记忆补全/LLM 猜测/
        // 继承等非用户指定值），也进入提取：提取成功则以用户输入中的真实企业名覆盖，
        // 提取失败由下方"提取兜底"清空 → 询问主体，避免垃圾主体直接查询误报"未查询到报告"。
        // ============================================================
        // 多意图标记生效时跳过兜底提取：多意图句子（如"风险识别和历史尽调报告"）本身不是企业名，
        // 兜底提取只会把功能词残渣（"和历史尽调报告"）当公司名，应直接询问主体；
        // 用户补充主体（pending 路径，无标记）仍走兜底提取（"小米科技" → 小米科技）。
        // 含码点击协议文本（CompanyNameSelector 候选"公司：xx\n统一信用代码：码"）必须无条件进入提取：
        // company_name 残留第一轮旧模糊词（如"小米"），且"小米科技"包含"小米"使下方 contains 判定
        // 误通过——不提取码就会用旧模糊词重查同一批候选死循环，穿插确认卡片永不弹出
        boolean inputHasCreditCode = java.util.regex.Pattern
                .compile("[0-9A-Z]{18}").matcher(userInput.toUpperCase()).find();
        if (!fromMultiIntent && creditCode.isEmpty() && !userInput.isEmpty()
                && (inputHasCreditCode || companyName.isEmpty() || !userInput.contains(companyName))) {
            // 1) 18 位统一信用代码
            java.util.regex.Matcher ccMatcher = java.util.regex.Pattern
                    .compile("[0-9A-Z]{18}").matcher(userInput.toUpperCase());
            if (ccMatcher.find()) {
                creditCode = ccMatcher.group();
            } else {
                // 2) "查XX的报告" / "XX的历史尽调报告" 结构提取
                String extracted = "";
                java.util.regex.Matcher qm = QUERY_COMPANY_PATTERN.matcher(userInput);
                if (qm.find()) {
                    extracted = qm.group(1).trim();
                    // 排除模板词本身被误提取（如"查询历史尽调报告"会提取到"历史"）
                    if (extracted.matches("历史|尽调|报告|历史尽调|尽调报告|历史报告")) extracted = "";
                    // 剔除口语填充词（如"找一下有没有小米的尽调报告"中"有没有"会被组 1 吞入）
                    extracted = extracted.replaceAll("(?:有没有|是否是|是不是|怎么|如何)", "").trim();
                }
                if (extracted.isEmpty()) {
                    // 3) 通用兜底：移除查询行为词/报告模板词后剩余内容视为企业名
                    //    （如 pending 第二轮回复"小米科技"、无"报告"词的"查一下小米科技"）
                    // 复合句保护：输入含并列连接词/分隔符（如"生成报告和查询历史尽调报告"）时，
                    // 剩余内容不可能是单一企业名（会清理成"生成和"垃圾名），跳过提取，
                    // 保持 companyName 为空 → 阶段一 info_needed 先询问主体
                    boolean compoundSentence = userInput.matches(".*[，。；、,;].*")
                            || userInput.matches(".*(?:和|与|还有|同时|顺便|以及|然后|也).*");
                    if (!compoundSentence) {
                        String cleaned = userInput
                                .replaceAll("历史尽调报告|尽调记录|尽调报告|历史报告|以往的尽调|历史尽调|尽调历史|历史查询|查看历史|查询历史|以前的报告|之前查过吗|查下历史记录", "")
                                .replaceAll("报告|尽调", "")
                                .replaceAll("(?:查一下|查下|帮我查下|帮我查询|帮我查|查询|查看|看看|找一下|搜索|我想查|我想|我要查|我要|帮我看看|帮我|麻烦|请|查)", "")
                                .replaceAll("[，。；、！？!?\\s：:]+", " ")
                                .replaceAll("^的+|的+$", "")
                                .trim();
                        if (cleaned.length() >= 2) extracted = cleaned;
                    }
                }
                // 复合句残留防护：第 2 层结构化提取也可能吞入连接词（如"查小米科技和华为的尽调报告"
                // →"小米科技和华为"）。连接词前后都有内容即判定为复合句残留，放弃提取——
                // 企业名本身几乎不含这些连接词，宁可退回 info_needed 询问主体
                if (!extracted.isEmpty()
                        && extracted.matches(".*\\S(?:和|与|还有|同时|顺便|以及|然后|也)\\S.*")) {
                    log.info("HistoricalDDQuerySkill 提取结果疑似复合句残留，放弃提取: '{}' (input='{}')",
                            extracted, userInput);
                    extracted = "";
                }
                // 问题句式防护：提取结果若含疑问词（"查什么报告"→"什么"、"历史尽调报告怎么查"→"怎么"），
                // 说明用户在提问而非提供企业名，放弃提取 → 阶段一询问主体
                if (!extracted.isEmpty() && QUESTION_PATTERN.matcher(extracted).find()) {
                    log.info("HistoricalDDQuerySkill 提取结果疑似问题句式，放弃提取: '{}' (input='{}')",
                            extracted, userInput);
                    extracted = "";
                }
                if (!extracted.isEmpty()) {
                    companyName = extracted;
                    log.info("HistoricalDDQuerySkill 从 _user_input 兜底提取企业名称: '{}' (input='{}')",
                            companyName, userInput);
                }
            }
        }
        // 提取兜底：无合法信用代码时，company_name 若非空又非用户本次输入提供的（上层记忆补全/
        // LLM 猜测/继承等填的非用户指定值），一律清空 → 下方阶段一询问主体。避免"查询历史尽调
        // 报告"（无主体）被垃圾主体直接拿去查询而误报"未查询到报告"；用户输入中含该名称（提取
        // 成功或用户直接提供）时保留，正常走名称匹配。
        if (creditCode.isEmpty() && !companyName.isEmpty() && !userInput.contains(companyName) && !broadcastSubject) {
            log.info("HistoricalDDQuerySkill 主体非用户本次提供，清空以询问主体: '{}' (input='{}')",
                    companyName, userInput);
            companyName = "";
        }

        // ============================================================
        // 阶段一：检查是否缺少企业名称/编号
        // ============================================================
        // 非法的 credit_code（如 LLM 误把公司名塞入该参数）视为未提供，避免查询层按代码过滤出错
        if (!isValidCreditCode(creditCode)) {
            creditCode = "";
        }
        // 参数合法性校验：意图识别 LLM 在"必须提取企业名称"的强指令下，对纯功能句/多意图混合句
        // （如"查询历史尽调报告"、"和生成报告"、"风险识别和历史尽调报告"）会猜测填充非企业名的
        // 垃圾值（如"风险识别"）。这类值直接拿去查询会误报"未查询到报告"且不询问主体。清洗功能词
        // 残渣：剩余 <2 字或 isFunctionalResidue 判定为功能词残渣（含多意图路径）→ 视为未提供主体
        // （清空后走下方阶段一询问主体）；剩余 ≥2 字且非残渣 → 用清洗结果替换（如"小米的尽调报告"→"小米"）。
        if (creditCode.isEmpty() && !companyName.isEmpty()) {
            String cleaned = companyName
                    .replaceAll("历史尽调报告|尽调记录|尽调报告|历史报告|以往的尽调|历史尽调|尽调历史|历史查询|查看历史|查询历史|以前的报告|之前查过吗|查下历史记录|企业名称|公司名称|客户名称|公司名|客户", "")
                    .replaceAll("报告|尽调|生成|制作|创建", "")
                    .replaceAll("(?:查一下|查下|帮我查下|帮我查询|帮我查|查询|查看|看看|找一下|搜索|我想查|我想|我要查|我要|帮我看看|帮我|麻烦|请|查)", "")
                    .replaceAll("(?:的|在|关于|请问|确认|一下)", "")
                    .replaceAll("[，。；、！？!?\\s：:（）()]+", "")
                    .trim();
            if (cleaned.length() < 2 || isFunctionalResidue(cleaned)) {
                log.info("HistoricalDDQuerySkill 参数 company_name 疑似功能句垃圾值/功能残渣，清空以询问主体: '{}' (input='{}')",
                        companyName, userInput);
                companyName = "";
            } else if (!cleaned.equals(companyName)) {
                log.info("HistoricalDDQuerySkill 清洗参数 company_name 功能词残渣: '{}' → '{}'",
                        companyName, cleaned);
                companyName = cleaned;
            }
        }
        if (creditCode.isEmpty() && companyName.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "info_needed");
            // 未到达步骤结束点：等待用户补充企业标识后重跑当前步骤
            result.put(Skill.KEY_STEP_DONE, false);
            result.put("message", "请问您要查询哪家企业的历史尽调报告？可提供企业名称或统一信用代码。");
            return result;
        }

        // ============================================================
        // 阶段二：企业名称模糊匹配 —— 用户以公司名（无信用代码）发起查询时，
        // 返回 candidates 选项卡供用户确认（可能存在同名不同信用代码的企业）。
        // 已有合法 credit_code 时跳过本阶段：主体已唯一确定（如规划前序步骤解析出的完整主体），
        // 无需再按名称匹配——名称索引仅含已生成报告的公司，匹配它反而会误报"未找到企业"。
        // ============================================================
        if (!companyName.isEmpty() && creditCode.isEmpty()) {
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

            // 名称索引未命中：索引仅含 report.json 中已生成报告的公司，未命中不代表企业不存在
            // （如规划前序步骤解析出的主体在报告库中尚无报告）。不再返回"未找到与「X」匹配的企业"，
            // 降级到阶段三/四按名称直接查询，由 queryReports 的名称包含匹配兜底，
            // 无报告时返回"未查询到报告"（语义：企业可能真实存在，只是没有历史尽调报告）。
            if (matches.isEmpty()) {
                log.info("HistoricalDDQuerySkill 阶段二名称索引未命中，降级按名称查询: companyName='{}', nameIndexSize={}",
                        companyName, nameIndex.size());
            }

            if (!matches.isEmpty()) {
                // 构造选项（含企业名称 + 统一社会信用代码）
                List<Map<String, Object>> options = new ArrayList<>();
                for (Map<String, Object> m : matches) {
                    Map<String, Object> opt = new LinkedHashMap<>();
                    opt.put("company_name", m.get("company_name"));
                    opt.put("credit_code", m.getOrDefault("credit_code", ""));
                    options.add(opt);
                }

                // 进入本阶段（用户以公司名发起）→ 一律展示选项卡让用户确认：
                // 可能存在同名不同信用代码的企业，必须由用户显式选择后才能查询
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("action", "candidates");
                // 未到达步骤结束点：等待用户点击候选企业后重跑当前步骤
                result.put(Skill.KEY_STEP_DONE, false);
                result.put("keyword", companyName);
                result.put("options", options);
                return result;
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
            // 到达步骤结束点：查询无报告，步骤完成
            result.put(Skill.KEY_STEP_DONE, true);
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
        // 到达步骤结束点：查询结果已返回，步骤完成
        result.put(Skill.KEY_STEP_DONE, true);
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

    /**
     * 功能词残渣判定：整体由功能词/疑问句式构成（如"风险识别""和历史尽调报告""信息核实"）时
     * 不是企业名。用于参数清洗后校验，防止 LLM 在多意图混合句/纯功能句中把功能词残渣填进
     * company_name 直接拿去查询。先剥首尾连接词（"和""以及"等），再检查疑问句式
     * （QUESTION_PATTERN）与功能词表整体匹配。真实企业名（如"XX风险投资"）含词表外字，不受影响。
     * 与 RiskCheckSkill/InformationCheckSkill 的 isFunctionalResidue 词表同源。
     */
    private static boolean isFunctionalResidue(String s) {
        if (s == null || s.isEmpty()) return true;
        String stripped = s.replaceFirst("^(?:和|并|及|或|与|以及)+", "")
                .replaceFirst("(?:和|并|及|或|与|以及)+$", "");
        if (stripped.isEmpty()) return true;
        if (QUESTION_PATTERN.matcher(stripped).find()) return true;
        return stripped.matches("(?:信息|核实|核验|核查|验证|执照|营业执照|风险|风控|风评|尽调|报告|查询|识别|融资|贷款|授信|调查|评估|历史|检索|搜索|生成|制作|创建|一下|一遍|下|遍)+");
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
        return CompanyNameExtractor.isValidCreditCode(code);
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
