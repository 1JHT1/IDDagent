package com.IDDagent.service;

import com.IDDagent.skill.ReportGenerateSkill;
import com.IDDagent.skill.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务规划服务：把多意图决策拆解为可穿插执行的步骤序列。
 * 规划模式与现有单意图路径完全兼容——任何环节失败都显式关闭规划，防止会话卡死。
 * 规划增强（Phase 5）：priority 排序、规划期参数预补全、共享参数一次收集广播、
 * 计划预览（plan_preview）与结果汇总（plan_summary）。
 */
@Service
public class IntentPlannerService {

    private static final Logger log = LoggerFactory.getLogger(IntentPlannerService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern COMPANY_PATTERN = Pattern.compile("公司\\s*[：:]\\s*(\\S+)");
    private static final Pattern CREDIT_CODE_PATTERN = Pattern.compile("[0-9A-Z]{18}");

    /** 候选点击协议文本前缀：前端候选卡片（InformationCheckCard/CompanyQueryCard 等）点击发送的固定句式动作词，
     * 提取企业名时整体剥除（"帮我核实{企业名}的信息"、"帮我查一下{企业名}{功能词}"） */
    private static final Pattern CANDIDATE_PROTOCOL_PREFIX = Pattern.compile(
            "^(?:帮我)?(?:核实|核验|核查|验证|查询|查一下|查下|查询一下)(?:一下|下|一遍)?\\s*");
    /** 候选点击协议文本尾部功能词：对应各候选卡片消息的后缀（CompanyQueryCard 的 SKILL_LABEL 等），
     * 长词在前避免短词（如"信息"）先截断长词（如"股东信息"） */
    private static final Pattern CANDIDATE_PROTOCOL_SUFFIX = Pattern.compile(
            "(?:的)?(?:股东信息|受益人信息|海关认证信息|海关失信名单信息|账户冻结标签|授信信息|人行账户管控信息|基本信息|企业族谱|信息|情况|风险)$");

    /**
     * 非主体回复判定：整体匹配功能残渣/确认词/人称前缀组合 → 不是企业名补充。
     * 与 InformationCheckSkill.isFunctionalResidue 词表保持同源，另加常见确认词。
     * 用于 mergeUserInput 裸企业名兜底解析：防止把"信息核实""好的"当企业名。
     */
    private static final Pattern NON_SUBJECT_REPLY = Pattern.compile(
            "^(?:(?:和|并|及|或|与|以及)+)?"
            + "(?:信息|核实|核验|核查|验证|执照|营业执照|风险|风控|风评|尽调|报告|查询|识别|融资|贷款|授信|调查|评估|历史|检索|搜索|看看|看下|查一下|查询一下|一下|一遍|下|遍|查)+"
            + "|(?:是的|好的|可以|继续|行|确定|确认|嗯|好|对|没问题|ok|OK|帮我|请|麻烦|要|想|的|关于)"
            + "$");

    /**
     * 疑问/问题句式判定：含典型疑问词（什么/怎么/是否/有没有/吗/呢 等）或提问类名词
     * （介绍/定义/含义/意思/包含/包括）。用户以问题句式表达时（如"风险识别是什么"、
     * "信息核实包括哪些内容"）是在提问而非提供企业名——规划层裸兜底与技能层兜底提取
     * 都必须放弃把这类输入当作主体，否则会把问题文本直接当公司名去查询。
     * 真实企业名（含"风险"字样的"XX风险投资"）不含疑问词，不受影响。
     * 与 RiskCheckSkill/InformationCheckSkill/HistoricalDDQuerySkill 的 QUESTION_PATTERN 同源。
     */
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "什么|哪些|哪个|怎么|如何|为什么|为啥|多少|有没有|是否|是不是|嘛|呢|吗|啥|干嘛|干什么|做什么|介绍|定义|含义|意思|包含|包括");

    /** 规划步骤内提示语与结果之间的间隔（毫秒）：提示语先发出，结果延迟返回，避免多任务"同时输出"的观感 */
    private static final long PLAN_STEP_GAP_MS = 500;

    /** 穿插标记词：用户明确插入新任务的表达（"顺便/另外/同时…"开头即视为新意图穿插，而非当前步骤的补充回复） */
    private static final Pattern INTERLEAVE_MARKER =
            Pattern.compile("^(顺便|顺便再|顺便帮我|另外|另外帮我|同时|插一句|先帮我|再帮我|帮我查一下别的)");

    private final ContextMemoryService contextMemoryService;
    private final SkillRegistry skillRegistry;
    private final ReportTaskStore reportTaskStore;
    private final ReportGenerateSkill reportGenerateSkill;
    private final UserStoreService userStoreService;

    public IntentPlannerService(ContextMemoryService contextMemoryService, SkillRegistry skillRegistry,
                                ReportTaskStore reportTaskStore, ReportGenerateSkill reportGenerateSkill,
                                UserStoreService userStoreService) {
        this.contextMemoryService = contextMemoryService;
        this.skillRegistry = skillRegistry;
        this.reportTaskStore = reportTaskStore;
        this.reportGenerateSkill = reportGenerateSkill;
        this.userStoreService = userStoreService;
    }

    /** 技能执行器回调：给定单意图 decision，返回该技能的 SSE 事件流（由 ChatController 注入避免循环依赖） */
    public interface SkillInvoker {
        Flux<String> invoke(Map<String, Object> decision);
    }

    /**
     * 将多意图决策转换为规划步骤列表（Phase 5 增强）：
     * 1. 按 priority 稳定升序排序（无 priority 的意图保持数组顺序，排在有 priority 的之后）
     * 2. 主体仅来自用户显式表达：LLM 解析参数或规划内前序步骤继承（主体继承 pass）——
     *    不沿用对话记忆（ctx）主体，缺主体的非 chat 步骤标记 needsInput，由技能层询问用户显式补充
     * 3. chat 意图 question 缺失时用 _user_input 补全
     * 4. needsInput 判定：非 chat 步骤且 company_name/credit_code 均缺失 → 需要用户补充
     */
    @SuppressWarnings("unchecked")
    public List<ContextMemoryService.PlanStep> buildPlan(List<Map<String, Object>> intents) {
        List<ContextMemoryService.PlanStep> steps = new ArrayList<>();
        if (intents == null) return steps;
        for (Map<String, Object> intent : intents) {
            String skill = (String) intent.getOrDefault("skill", "");
            if (skill == null || skill.isBlank()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> params = new LinkedHashMap<>(
                    (Map<String, Object>) intent.getOrDefault("params", Map.of()));
            // 读取 priority（LLM 输出，缺失默认 0 按数组顺序）
            Object rawPriority = intent.get("priority");
            int priority = rawPriority instanceof Number n ? n.intValue() : 0;

            // chat 意图（非技能问答）：直接回答 params.question，永不等待补充企业参数
            if ("chat".equals(skill)) {
                if (!params.containsKey("question") && params.get("_user_input") != null) {
                    params.put("question", String.valueOf(params.get("_user_input")));
                }
                steps.add(new ContextMemoryService.PlanStep(skill, params, false, priority));
                continue;
            }

            // 多意图标记注入（Phase 8）：风险识别/信息核实/历史尽调报告与企业查询类技能
            // （CompanyQuerySkill 9 个：基本信息/股东/受益人/族谱/海关认证/海关失信/冻结/授信/人行账管）
            // 在多意图规划中，即使 LLM 解析出了主体也可能填的是功能词残渣（如"股东信息""风险识别"），
            // 且无主体时技能层从 _user_input 兜底提取会把多意图句子的残余（如"和风险识别""下和风险识别"）
            // 当公司名→误报未找到。技能层据此标记：有主体 → 先清洗功能词残渣（清洗后为空 → 询问主体）；
            // 无主体 → 跳过 _user_input 兜底提取直接询问。这些技能的查询/核实/风险识别主体必须由用户
            // 显式提供，多意图句子本身不是企业名。
            if ("query_due_diligence_reports".equals(skill)
                    || "verify_business_license".equals(skill)
                    || "check_company_risk".equals(skill)
                    || "query_company_basic_info".equals(skill)
                    || "query_shareholder_info".equals(skill)
                    || "query_beneficiary_info".equals(skill)
                    || "query_company_genealogy".equals(skill)
                    || "query_customs_auth".equals(skill)
                    || "query_customs_blacklist".equals(skill)
                    || "query_account_freeze_tag".equals(skill)
                    || "query_credit_granting".equals(skill)
                    || "query_pboc_account_control".equals(skill)) {
                params.put("_from_multi_intent", true);
            }

            // 非 chat 步骤不沿用对话记忆（ctx）主体：规划步骤的主体只接受用户显式表达
            // （LLM 解析参数 / 前序步骤继承 / 等待输入时用户补充），缺主体直接标记 needsInput，
            // 执行时由技能层询问用户——避免把其他业务场景（风险/融资等）的旧记忆主体悄悄套用
            // 到本步骤，误报"未查询到报告"/"未找到匹配企业"且用户无法察觉。
            boolean needsInput = !params.containsKey("company_name")
                    && !params.containsKey("credit_code");
            steps.add(new ContextMemoryService.PlanStep(skill, params, needsInput, priority));
        }

        // 稳定排序：有 priority 的按 (priority, 原下标) 升序；无 priority 的（默认 0）保持数组顺序排在有 priority 的之后
        // （List.sort 为稳定排序，同 key 元素保持原顺序）
        steps.sort((a, b) -> {
            if (a.priority > 0 && b.priority > 0) return Integer.compare(a.priority, b.priority);
            if (a.priority > 0) return -1;
            if (b.priority > 0) return 1;
            return 0;
        });

        // 主体继承 pass（Phase 6）：规划步骤未显式提供主体（用户对话未给出、LLM 未解析出）时，
        // 按最终执行顺序自动继承最近前序步骤的主体——“下一步没变换主体就默认前一个主体”。
        // 场景：“查小米的风险，再查融资情况”——第二步 LLM 未解析出主体，自动沿用第一步的小米，
        // 避免 needsInput 误判让用户重复输入；多主体隔离不受影响（已有自身主体的步骤不参与继承，
        // 且成为后续无主体步骤的继承源，“查小米再查华为再查融资”第三步继承华为）。
        String lastCompanyName = null;
        String lastCreditCode = null;
        for (ContextMemoryService.PlanStep step : steps) {
            boolean hasSubject = step.params.containsKey("company_name") || step.params.containsKey("credit_code");
            if (!"chat".equals(step.skill) && !hasSubject) {
                boolean inherited = false;
                if (lastCreditCode != null && !lastCreditCode.isEmpty()) {
                    step.params.put("credit_code", lastCreditCode);
                    if (lastCompanyName != null && !lastCompanyName.isEmpty()) {
                        step.params.put("company_name", lastCompanyName);
                    }
                    inherited = true;
                } else if (lastCompanyName != null && !lastCompanyName.isEmpty()) {
                    step.params.put("company_name", lastCompanyName);
                    inherited = true;
                }
                if (inherited) {
                    step.needsInput = false;
                    // 标记：本步骤主体来自静态继承占位（如仅简称 company_name，无 credit_code），
                    // 并非用户显式绑定。后续 runPlan 执行前会据此重新继承前序步骤执行期解析出的
                    // 完整主体；broadcastParams/mergeUserInput 也会据此允许真实主体覆盖占位值。
                    step.params.put("_inherited_subject", true);
                    if (step.status == ContextMemoryService.PlanStatus.WAITING_INPUT) {
                        step.status = ContextMemoryService.PlanStatus.PENDING;
                    }
                    log.info("Inherited subject for plan step '{}': company_name={}, credit_code={}",
                            step.skill, step.params.get("company_name"), step.params.get("credit_code"));
                }
            }
            // 更新最近主体链：本步骤解析出的主体作为后续无主体步骤的继承源
            Object cn = step.params.get("company_name");
            if (cn != null) lastCompanyName = String.valueOf(cn);
            Object cc = step.params.get("credit_code");
            if (cc != null) lastCreditCode = String.valueOf(cc);
        }
        return steps;
    }

    /**
     * 共享参数广播（Phase 5）：把已确认的关键参数（company_name/credit_code）应用到
     * 规划中所有缺失该键的步骤。应用后某步骤拥有任一关键参数 → needsInput=false。
     * 效果：用户只补充一次企业名，后续所有依赖它的步骤直接执行，不再重复询问。
     * 主体参数（company_name/credit_code）广播前做主体一致性检查：已有自己的主体标识
     * （company_name/credit_code 任一）的步骤视为已绑定主体，不接收其他主体的广播——
     * 防止"查小米风险再查华为"时第一步解析出的小米 credit_code 被广播进第二步（华为），
     * 导致第二步按码查询返回小米的风险（主体未切换）。
     */
    public void broadcastParams(String convId, String key, Object value) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx == null || !ctx.planActive || ctx.pendingPlan.isEmpty()) return;
        if (key == null || value == null) return;
        boolean isSubjectKey = "company_name".equals(key) || "credit_code".equals(key);
        int applied = 0;
        for (ContextMemoryService.PlanStep step : ctx.pendingPlan) {
            // 带继承标记的步骤其主体是静态占位值（非真实绑定），允许广播的真实主体覆盖已有键；
            // 普通步骤已有该键则跳过，保持"一次问清、不重复覆盖"的语义
            boolean inheritedPlaceholder = Boolean.TRUE.equals(step.params.get("_inherited_subject"));
            if (step.params.containsKey(key) && !(inheritedPlaceholder && isSubjectKey)) continue;
            if (isSubjectKey && !inheritedPlaceholder
                    && (step.params.containsKey("company_name") || step.params.containsKey("credit_code"))) {
                log.info("Skip broadcasting {}='{}' to step '{}' (already bound to its own subject)",
                        key, value, step.skill);
                continue;
            }
            step.params.put(key, value);
            // 广播主体标记：广播的 company_name/credit_code 来自用户对"请提供企业名称"询问的显式回答
            // （mergeUserInput 解析）或技能解析（info_needed 响应），可信度等同用户输入。但目标步骤的
            // _user_input 仍是 buildPlan 注入的原始多意图句子（不含该主体），技能层"主体非用户本次
            // 输入"校验会据此把广播主体清空 → 再次询问 → 多意图流程每步都重复问主体（表现为"回答后
            // 一直循环"）。打标记让技能层跳过清空/功能词清洗，广播主体直接生效（一次问清）。
            if (isSubjectKey) {
                step.params.put("_broadcast_subject", true);
            }
            applied++;
            if (step.params.containsKey("company_name") || step.params.containsKey("credit_code")) {
                step.needsInput = false;
            }
        }
        if (applied > 0) {
            log.info("Broadcast param '{}' to {} plan step(s)", key, applied);
        }
    }

    /**
     * 合并用户输入到当前规划步骤参数（Phase 5 增强）：
     * - 输入为 JSON 对象 → 解析合并（clarification 选项回复）
     * - 含 "公司：xx" → 提取 company_name；含 18 位信用代码 → 提取 credit_code
     * - 其余情况仅注入 _user_input
     * 合并后 needsInput = false，等待重跑当前步骤；解析出的关键参数广播到其他缺失步骤（一次问清）。
     */
    @SuppressWarnings("unchecked")
    public void mergeUserInput(String convId, ContextMemoryService.PlanStep step, String userInput) {
        if (step == null || userInput == null || userInput.isBlank()) return;
        String trimmed = userInput.trim();
        Map<String, Object> params = step.params;

        // chat 步骤（非技能问答）：用户输入整体作为待回答的问题（防御性：正常流程 chat 步骤不等待输入）
        if ("chat".equals(step.skill) && !params.containsKey("question")) {
            params.put("question", userInput);
        }

        // 静态继承占位标记：params 中主体仅来自 buildPlan 静态继承（非用户显式绑定，可能只是简称
        // 或无完整码）。此时用户本次输入的新主体必须能覆盖占位值——否则占位值会一直阻塞新名称写入
        // （技能层又因"主体非用户提供"清空占位 → 反复询问"请提供企业名称"死循环）。
        boolean inheritedPlaceholder = Boolean.TRUE.equals(params.get("_inherited_subject"));

        // 本次解析出的关键参数，合并后广播到其他缺失步骤
        Map<String, Object> resolved = new LinkedHashMap<>();

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> json = mapper.readValue(trimmed, Map.class);
                json.forEach(params::put);
                if (json.containsKey("company_name")) resolved.put("company_name", json.get("company_name"));
                if (json.containsKey("credit_code")) resolved.put("credit_code", json.get("credit_code"));
                // JSON 显式提供新主体 → 清掉未提供的那部分旧占位主体键，防止新旧主体混用
                // （占位码=旧主体 + 新名称，技能按码查询会串扰回旧主体）
                if (inheritedPlaceholder) {
                    if (json.containsKey("company_name") && !json.containsKey("credit_code")) {
                        params.remove("credit_code");
                    } else if (json.containsKey("credit_code") && !json.containsKey("company_name")) {
                        params.remove("company_name");
                    }
                }
                log.info("Merged JSON user input into plan step params: {}", json.keySet());
            } catch (Exception e) {
                log.warn("User input looks like JSON but failed to parse, fallback to text extraction: {}", e.getMessage());
            }
        }

        // 空值键视为缺失：技能层可能把功能词残渣清洗后返回空主体（handleSingleSkill info_needed 分支
        // 写回空字符串或移除垃圾键），若仅按 containsKey 判断会认为"已有主体"而拒绝覆盖用户新补充的
        // 企业名 → 每次重跑仍询问 → 死循环。
        boolean hasCompanyName = params.containsKey("company_name")
                && !String.valueOf(params.get("company_name")).trim().isEmpty();
        boolean hasCreditCode = params.containsKey("credit_code")
                && !String.valueOf(params.get("credit_code")).trim().isEmpty();

        // company_name：params 无该键、该键为空值，或该键仅是从前序步骤继承的静态占位值时，解析用户输入覆盖
        if (!hasCompanyName || inheritedPlaceholder) {
            Matcher m = COMPANY_PATTERN.matcher(trimmed);
            if (m.find()) {
                params.put("company_name", m.group(1).trim());
                resolved.put("company_name", params.get("company_name"));
                // 新名称替换旧占位主体 → 旧占位码对应的是旧主体，一并清除防止按码串扰
                if (inheritedPlaceholder) {
                    params.remove("credit_code");
                }
            }
        }
        if (!hasCreditCode || inheritedPlaceholder) {
            Matcher m = CREDIT_CODE_PATTERN.matcher(trimmed);
            if (m.find()) {
                params.put("credit_code", m.group(0));
                resolved.put("credit_code", params.get("credit_code"));
                // 新码替换旧占位码 → 旧占位名称不再匹配新码，一并清除
                if (inheritedPlaceholder) {
                    params.remove("company_name");
                }
            }
        }

        // 候选点击协议文本主体提取：候选卡片（InformationCheckCard/CompanyQueryCard 等）点击发送的
        // 固定句式"帮我核实{企业名}的信息"、"帮我查一下{企业名}{功能词}"不含信用代码与"公司："，
        // 若不提取中间的新企业名覆盖 params，旧的模糊查询词（如"小米"）会保留，重跑仍匹配同一批
        // 候选 → 点击候选后循环模糊。含 18 位码的协议文本（RiskCheckCard 的"查询统一信用代码为
        // xxx的客户的风险"）以码为主体（码已在上方提取），跳过名称提取避免把整句当企业名。
        // 候选点击协议主体提取：字段名协议（"公司：X\n统一信用代码：Y"）无论是否含 18 位码，
        // X 都是用户显式点击的主体，必须提取覆盖旧 company_name（含码时 LLM/旧主体可能残留）；
        // 动作词协议（"查询统一信用代码为xxx的客户的风险"）含码时以码为主体，跳过名称提取
        boolean fieldProtocol = COMPANY_PATTERN.matcher(trimmed).find();
        if (!"chat".equals(step.skill) && (fieldProtocol
                || (!hasCreditCode && !CREDIT_CODE_PATTERN.matcher(trimmed).find()))) {
            String nameFromProtocol = extractNameFromCandidateProtocol(trimmed);
            if (nameFromProtocol != null) {
                params.put("company_name", nameFromProtocol);
                resolved.put("company_name", nameFromProtocol);
                // 候选点击确认标记：技能层据此跳过"二次弹选项卡"直接查询（无码公司点击场景）；
                // 非协议输入不置此标记，用户手动输入精确名称仍会弹选项卡确认（防同名不同码）
                params.put("_candidate_clicked", true);
                if (inheritedPlaceholder) {
                    params.remove("credit_code");
                }
                log.info("Merged candidate-click company name from protocol text: '{}'", nameFromProtocol);
            }
        }

        // 裸企业名兜底（Phase 7）：规划步骤等待输入时用户直接回复企业名（如"小米"），
        // COMPANY_PATTERN("公司：xx") 不会命中，但这是对"请提供企业名称"询问的直接回答，
        // 应整体提取为主体并广播——否则后续规划步骤仍缺主体会再次询问（违背"一次问清"）。
        // 用 NON_SUBJECT_REPLY 过滤功能残渣/确认词，防止把"信息核实""好的"当企业名。
        // 继承占位主体（无论仅简称还是带占位码）同样允许覆盖：占位是旧主体的静态值，
        // 用户新回复的名称优先，覆盖时清掉旧占位码避免新旧主体混用。
        // 已由候选协议文本提取出主体（resolved 含 company_name）时跳过，防止整句协议文本
        // （"帮我核实小米食品有限公司的信息"）被当作企业名整体提取。
        if (!"chat".equals(step.skill)
                && !resolved.containsKey("company_name")
                && (!hasCreditCode || inheritedPlaceholder)
                && (!hasCompanyName || inheritedPlaceholder)
                && isLikelySubjectReply(trimmed)) {
            params.put("company_name", trimmed);
            if (inheritedPlaceholder) {
                params.remove("credit_code");
            }
            resolved.put("company_name", trimmed);
            log.info("Merged bare company name from user input: '{}'", trimmed);
        }

        // 用户显式提供了主体（JSON/"公司：xx"/18 位码解析）→ 清除静态继承标记：
        // 用户给的主体优先级最高，不允许后续 runPlan 继承逻辑用前序占位主体覆盖用户的选择
        if (!resolved.isEmpty()) {
            params.remove("_inherited_subject");
        }
        params.put("_user_input", userInput);
        step.needsInput = false;
        if (step.status == ContextMemoryService.PlanStatus.WAITING_INPUT) {
            step.status = ContextMemoryService.PlanStatus.PENDING;
        }

        // 共享参数广播：本次确认的企业主体应用到规划中其他缺失该键的步骤（一次问清）
        if (convId != null) {
            resolved.forEach((k, v) -> broadcastParams(convId, k, v));
        }
    }

    /**
     * 判断规划步骤等待输入时的用户回复是否像"主体补充"（可整体作为企业名提取）：
     * 长度 2~50、非问题句式、且整体不匹配功能残渣/确认词组合（如"信息核实""风险识别""好的"）。
     * 只过滤纯功能句、问题句与短确认词，正常企业名（含"风险"字样如"XX风险投资"）不受影响。
     */
    private boolean isLikelySubjectReply(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 2 || t.length() > 50) return false;
        // 问题句式（"信息核实是什么"）是提问而非主体补充，不整体提取为企业名 → 先询问主体
        if (QUESTION_PATTERN.matcher(t).find()) return false;
        return !NON_SUBJECT_REPLY.matcher(t).matches();
    }

    /**
     * 从候选点击协议文本中提取中间的企业名：前端候选卡片点击发送固定句式
     * "帮我核实{企业名}的信息" / "帮我查一下{企业名}{功能词}" / "查询统一信用代码为xxx的客户的风险"，
     * 剥除动作前缀与尾部功能词后剩余部分即候选企业名（如"小米食品有限公司"）。
     * 仅当文本以动作词开头才视为协议文本（防误伤普通用户输入）；企业名内部含功能词
     * （如"小米信息科技有限公司"）不受影响——先剥前缀、再按尾部锚定剥后缀。
     */
    private String extractNameFromCandidateProtocol(String text) {
        if (text == null) return null;
        // 候选卡片字段名协议（CompanyNameSelector/InformationCheckCard 点击发送的
        // "公司：X\n统一信用代码：Y"）：直接取"公司："后的名称。Y 为空（无码公司，
        // 选项 credit_code 已被技能层置空）时同样生效——X 即用户显式选择的主体，
        // 必须识别出来，否则点击无码候选后主体丢失 → 二次弹卡/反复询问
        Matcher cm = COMPANY_PATTERN.matcher(text);
        if (cm.find()) {
            String n = cm.group(1).replaceAll("[，。；、！？!?\\s：:（）()]+", "").trim();
            n = n.replaceAll("统一信用代码.*$", "").trim();
            if (n.length() >= 2) return n;
        }
        Matcher pm = CANDIDATE_PROTOCOL_PREFIX.matcher(text);
        if (!pm.find()) return null;   // 必须以动作词开头才视为协议文本
        String name = pm.replaceFirst("")
                .replaceAll("[，。；、！？!?\\s：:（）()]+", "")
                .trim();
        name = CANDIDATE_PROTOCOL_SUFFIX.matcher(name).replaceFirst("");
        name = name.replaceAll("^的+|的+$", "");
        if (name.length() < 2) return null;
        return name;
    }

    /**
     * 从用户输入中提取候选点击协议文本的企业名（供 ChatController 单技能/pendingSkill 重跑场景使用）：
     * 文本是候选卡片点击的固定句式且不含 18 位信用代码时返回中间的企业名，否则返回 null。
     * 含码的协议文本（RiskCheckCard 的"查询统一信用代码为xxx的客户的风险"）以码为主体，码由技能层
     * 解析，名称提取会得到整句垃圾——直接跳过。
     */
    public String extractCandidateClickName(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;
        // 字段名协议（"公司：X\n统一信用代码：Y"，CompanyNameSelector/InformationCheckCard 点击）：
        // X 即用户显式点击的主体，无论 Y 是否 18 位码都必须提取覆盖旧 company_name——
        // 含码时 LLM 意图解析可能把对话历史中的旧主体填进 company_name，不覆盖会查错主体
        if (COMPANY_PATTERN.matcher(userInput).find()) {
            return extractNameFromCandidateProtocol(userInput);
        }
        // 含 18 位码的动作词协议（RiskCheckCard 的"查询统一信用代码为xxx的客户的风险"）以码为主体，
        // 名称提取会得到整句垃圾——直接跳过
        if (CREDIT_CODE_PATTERN.matcher(userInput).find()) return null;
        return extractNameFromCandidateProtocol(userInput);
    }

    /**
     * 执行当前规划步骤：首次执行（planIndex==0）先发 plan_preview 计划预览事件，
     * 再发 plan_progress 事件（第 x/N 步），构造单意图 decision 交给技能执行器。
     * 任一步骤失败显式关闭规划（planActive=false），防止会话永久卡在规划态。
     * 步骤真正结束后由 stepDoneAndConfirm 发确认卡片暂停，等待用户确认是否继续下一步（不自动推进）。
     */
    public Flux<String> runPlan(String convId, SkillInvoker skillInvoker) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        ContextMemoryService.PlanStep step = contextMemoryService.getCurrentPlanStep(convId);
        if (step == null || !ctx.planActive) {
            log.warn("runPlan called without active plan step, clearing plan to avoid stuck conversation");
            contextMemoryService.clearPendingPlan(convId);
            return Flux.empty();
        }
        step.status = ContextMemoryService.PlanStatus.RUNNING;
        int total = ctx.pendingPlan.size();
        int idx = ctx.planIndex + 1;
        boolean isChat = "chat".equals(step.skill);
        String stepName = SkillRegistry.displayName(step.skill);
        String planText = "第 " + idx + "/" + total + " 步：" + stepName;

        // 执行前主体兜底继承（Phase 6）：当前步骤缺主体、或主体仅来自 buildPlan 静态继承占位
        // （带 _inherited_subject 标记，如只有简称 company_name 无 credit_code）时，从最近的前序
        // 步骤重新继承。覆盖"步骤执行中才解析出完整主体"的场景（如第一步 info_needed/result 才
        // 带回 credit_code，静态继承时该主体尚未出现）；只读规划内前序步骤 params，不读全局 ctx
        // 记忆——避免旧会话主体串扰；显式绑定自身主体（无标记）的步骤不受影响（多主体隔离保持）。
        boolean stepHasOwnSubject = step.params.containsKey("company_name")
                || step.params.containsKey("credit_code");
        boolean stepSubjectInherited = Boolean.TRUE.equals(step.params.get("_inherited_subject"));
        if (!isChat && (!stepHasOwnSubject || stepSubjectInherited)) {
            for (int i = ctx.planIndex - 1; i >= 0; i--) {
                ContextMemoryService.PlanStep prev = ctx.pendingPlan.get(i);
                // 跳过同样带继承标记的前序步骤：其主体也是静态占位（无执行期解析的完整值），
                // 继承它会形成"静态值链"而永远拿不到完整主体（如"查小米风险→生成报告→查询历史
                // 尽调报告"中第二/三步都静态继承"小米"，若第三步继承第二步则永远缺 credit_code）
                if (Boolean.TRUE.equals(prev.params.get("_inherited_subject"))) continue;
                Object cc = prev.params.get("credit_code");
                Object cn = prev.params.get("company_name");
                if (cc != null && !String.valueOf(cc).isEmpty()) {
                    step.params.put("credit_code", cc);
                    if (cn != null && !String.valueOf(cn).isEmpty()) {
                        step.params.put("company_name", cn);
                    }
                    step.needsInput = false;
                    step.params.remove("_inherited_subject");
                    log.info("Inherited subject from previous plan step {} for '{}': credit_code={}",
                            i + 1, step.skill, cc);
                    break;
                }
                if (cn != null && !String.valueOf(cn).isEmpty()) {
                    step.params.put("company_name", cn);
                    step.needsInput = false;
                    step.params.remove("_inherited_subject");
                    log.info("Inherited company_name from previous plan step {} for '{}': company_name={}",
                            i + 1, step.skill, cn);
                    break;
                }
            }
        }

        log.info("Plan step {}/{} running: {} params: {}", idx, total, step.skill, step.params);

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", isChat ? "chat" : "skill");
        decision.put("skill", step.skill);
        decision.put("params", step.params);
        decision.put("reason", "规划任务第 " + idx + " 步");

        Flux<String> invokeFlux = skillInvoker.invoke(decision);
        // chat 步骤（非技能问答）：回答完成后由规划器发确认卡片暂停（步骤间确认）
        // （技能步骤由 handleSingleSkill 在 result/not_found/error 时同样发确认卡片暂停）
        if (isChat) {
            invokeFlux = invokeFlux.concatWith(Flux.defer(() -> stepDoneAndConfirm(convId, skillInvoker)));
        }

        // 执行事件：首次执行/非首步统一发 plan_status 状态快照（面板渲染：整体计划 + 当前步骤执行中），
        // 替代原 plan_preview 预览气泡与“第 x/N 步”进度气泡；技能步骤延迟一小段间隔后再返回结果，
        // 让“上一步确认 → 本步面板更新 → 本步结果”节奏分明（技能为毫秒级本地查询，若不停顿，
        // 面板更新会与结果合并进同一次网络 flush，前端一次性渲染，观感变成“同时输出”）
        Flux<String> prefix = Flux.just(planStatusEvent(convId));
        if (ctx.planIndex > 0 && !isChat) {
            invokeFlux = invokeFlux.delaySubscription(Duration.ofMillis(PLAN_STEP_GAP_MS));
        }
        return prefix
                .concatWith(invokeFlux)
                .doOnError(e -> {
                    log.error("Plan step {} failed: {}", step.skill, e.getMessage());
                    contextMemoryService.clearPendingPlan(convId);
                });
    }

    /**
     * 步骤真正结束（result/not_found/ambiguous/summary/detail/error）后调用：
     * 标记当前步骤 DONE（FAILED 步骤保留其状态），然后<b>不再自动推进</b>：
     * - 还有下一步 → 设置 planConfirming 并发送 plan_step_confirm 确认卡片，暂停等待用户确认；
     * - 已是最后一步 → 发 plan_summary 结果汇总并关闭规划。
     * 用户确认继续后由 confirmContinue 推进到下一步。
     */
    public Flux<String> stepDoneAndConfirm(String convId, SkillInvoker skillInvoker) {
        return stepDoneAndConfirm(convId, skillInvoker, null);
    }

    /**
     * 步骤结束发确认卡（doneNote 非空时确认卡文案携带该说明，用于"以上选项均不是"跳过等场景）。
     */
    public Flux<String> stepDoneAndConfirm(String convId, SkillInvoker skillInvoker, String doneNote) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx == null || !ctx.planActive) return Flux.empty();

        ContextMemoryService.PlanStep current = contextMemoryService.getCurrentPlanStep(convId);
        if (current != null && current.status != ContextMemoryService.PlanStatus.DONE
                && current.status != ContextMemoryService.PlanStatus.FAILED) {
            current.status = ContextMemoryService.PlanStatus.DONE;
        }

        // 检查是否还有下一步（不推进 planIndex，确认后才推进）
        int nextIdx = ctx.planIndex + 1;
        if (nextIdx < ctx.pendingPlan.size()) {
            ContextMemoryService.PlanStep next = ctx.pendingPlan.get(nextIdx);
            contextMemoryService.setPlanConfirming(convId, true);
            log.info("Plan step {}/{} done, waiting for user confirmation before step {}",
                    ctx.planIndex + 1, ctx.pendingPlan.size(), nextIdx + 1);
            // 先发状态快照（当前步 DONE + confirming=true，面板更新完成态），再发步骤确认卡片
            return Flux.just(planStatusEvent(convId), planStepConfirmEvent(convId, ctx, next, doneNote));
        }

        // 最后一步完成，直接收尾（无需确认）
        log.info("Plan finished for conversation {}", convId);
        String summaryText = buildPlanSummary(ctx);
        // 只发终态快照（全部步骤 DONE + 汇总文本）：各步骤状态与完成态已由规划面板终态展示，
        // 不再发"任务完成"汇总气泡（plan_progress），避免消息流中面板之外出现重复的收尾文本。
        // 最终状态快照（全部步骤 DONE + 汇总文本）必须在 clearPendingPlan 之前发出，否则步骤列表已被清空
        Flux<String> tail = Flux.just(planStatusEvent(convId, summaryText));
        contextMemoryService.clearPendingPlan(convId);
        // 意图穿插恢复：本规划若是穿插期间的新规划，收尾后立即断点再续挂起的旧规划
        Flux<String> resume = resumePlanIfSuspended(convId, skillInvoker);
        return tail.concatWith(resume);
    }

    /**
     * 用户确认继续下一步（点击确认卡片"继续"按钮或输入继续类文本）：
     * 关闭确认标记 → 推进 planIndex → 还有下一步则执行，否则兜底收尾。
     */
    public Flux<String> confirmContinue(String convId, SkillInvoker skillInvoker) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx == null || !ctx.planActive) return Flux.empty();
        contextMemoryService.setPlanConfirming(convId, false);
        boolean hasNext = contextMemoryService.advancePlan(convId);
        if (hasNext) {
            log.info("User confirmed, advancing to plan step {}", ctx.planIndex + 1);
            return runPlan(convId, skillInvoker);
        }
        // 兜底：理论上前一步 stepDoneAndConfirm 已收尾，这里防御性收尾
        log.info("User confirmed but no next step, finishing plan");
        String summaryText = buildPlanSummary(ctx);
        // 最终状态快照（须在 clearPendingPlan 之前发出）
        Flux<String> tail = Flux.just(planStatusEvent(convId, summaryText));
        contextMemoryService.clearPendingPlan(convId);
        // 意图穿插恢复：同 stepDoneAndConfirm 收尾逻辑，防御性断点再续
        Flux<String> resume = resumePlanIfSuspended(convId, skillInvoker);
        return tail.concatWith(resume);
    }

    /**
     * 用户选择结束规划（点击确认卡片"结束"按钮或输入结束类文本）：
     * 关闭确认标记并清空规划，发送结束提示（已完成的步骤保留在对话中）。
     */
    public Flux<String> confirmStop(String convId, SkillInvoker skillInvoker) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx == null || !ctx.planActive) return Flux.empty();
        contextMemoryService.setPlanConfirming(convId, false);
        int doneCount = 0;
        for (ContextMemoryService.PlanStep step : ctx.pendingPlan) {
            if (step.status == ContextMemoryService.PlanStatus.DONE) doneCount++;
        }
        // 结束反馈文案随状态快照进入规划面板（summary 字段），须在 clearPendingPlan 之前发出
        String note = "任务已结束，已完成 " + doneCount + " 步。如需继续其他任务，请直接告诉我。";
        Flux<String> tail = Flux.just(planStatusEvent(convId, note));
        contextMemoryService.clearPendingPlan(convId);
        log.info("User chose to stop plan, {} step(s) completed", doneCount);
        // 意图穿插恢复：用户结束的是穿插期间的新规划时，恢复挂起的旧规划继续
        Flux<String> resume = resumePlanIfSuspended(convId, skillInvoker);
        return tail.concatWith(resume);
    }

    /** 拼接规划收尾文案：不再逐步骤列举（各步骤状态与中文摘要已在规划面板/终态快照中展示，
     * 避免"任务完成：1. xxx 2. xxx"列表式冗余输出），只输出简洁中文收尾语；有失败步骤时
     * 给出失败数量提示（失败明细由面板徽章与步骤摘要承担）。
     */
    public String buildPlanSummary(ContextMemoryService.ConversationContext ctx) {
        if (ctx == null || ctx.pendingPlan.isEmpty()) return "任务完成";
        int failedCount = 0;
        for (ContextMemoryService.PlanStep step : ctx.pendingPlan) {
            if (step.status == ContextMemoryService.PlanStatus.FAILED) failedCount++;
        }
        if (failedCount == 0) return "任务完成";
        return "任务完成，其中 " + failedCount + " 项未完成";
    }

    /**
     * 构造 plan_status SSE 事件（任务规划状态快照，供前端规划面板渲染与顶部状态栏同步）：
     * 注意：与其他 SSE 事件一致，快照字段拍平到事件顶层（steps/index/active/confirming/suspended/planId/summary），
     * 前端 agent.ts 按顶层剩余字段解析 event.data；字段集合与 GET /api/plan/{conversationId}/status
     * 返回的 plan 字段保持一致。
     * 注意：快照在 clearPendingPlan/discardSuspendedPlan 之后步骤列表为空，调用方须在这些操作之前发事件。
     */
    public String planStatusEvent(String convId) {
        return planStatusEvent(convId, null);
    }

    /** 构造带收尾汇总文本的 plan_status SSE 事件（summary 为面板终态文案） */
    public String planStatusEvent(String convId, String summary) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            Map<String, Object> data = contextMemoryService.getPlanStatusData(convId);
            if (summary != null && !summary.isEmpty()) {
                data.put("summary", summary);
            }
            event.put("type", "plan_status");
            // 拍平：与 sseEvent 的 putAll 行为一致，避免前端解析出 {data:{...}} 双层嵌套
            event.putAll(data);
            if (convId != null) {
                event.put("conversation_id", convId);
            }
            return mapper.writeValueAsString(event) + "\n\n";
        } catch (Exception e) {
            return "{\"type\":\"plan_status\",\"active\":false,\"steps\":[]}\n\n";
        }
    }

    /** 构造 plan_progress SSE 事件（与 ChatController.sseEvent 同格式：content 字段承载文案） */
    public String planProgressEvent(String convId, String text) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "plan_progress");
            event.put("content", text);
            if (convId != null) {
                event.put("conversation_id", convId);
            }
            return mapper.writeValueAsString(event) + "\n\n";
        } catch (Exception e) {
            return "{\"type\":\"plan_progress\",\"content\":\"任务执行中\"}\n\n";
        }
    }

    /**
     * 构造 plan_step_confirm SSE 事件（步骤完成后询问用户是否继续下一步）：
     * content 为卡片文案，current_step/total_steps/next_step 供前端确认卡片展示与按钮发送确认协议。
     */
    private String planStepConfirmEvent(String convId, ContextMemoryService.ConversationContext ctx,
                                        ContextMemoryService.PlanStep nextStep, String doneNote) {
        try {
            boolean isChat = "chat".equals(nextStep.skill);
            String displayName = SkillRegistry.displayName(nextStep.skill);
            Object company = nextStep.params.get("company_name");
            String companyStr = company == null ? "" : String.valueOf(company);
            String nextDesc = displayName;
            if (!isChat && companyStr != null && !companyStr.isBlank()) {
                nextDesc += "（" + companyStr + "）";
            }
            int total = ctx.pendingPlan.size();
            int doneIdx = ctx.planIndex + 1;
            String content;
            if (doneNote != null && !doneNote.isBlank()) {
                content = doneNote + "，是否继续执行第 " + (doneIdx + 1) + "/" + total + " 步（" + nextDesc + "）？";
            } else {
                content = "第 " + doneIdx + "/" + total + " 步已完成，是否继续执行第 " + (doneIdx + 1)
                        + "/" + total + " 步（" + nextDesc + "）？";
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "plan_step_confirm");
            event.put("content", content);
            event.put("current_step", doneIdx);
            event.put("total_steps", total);
            event.put("next_step", nextDesc);
            if (convId != null) {
                event.put("conversation_id", convId);
            }
            return mapper.writeValueAsString(event) + "\n\n";
        } catch (Exception e) {
            return "{\"type\":\"plan_step_confirm\",\"content\":\"是否继续下一步？\"}\n\n";
        }
    }


    /**
     * 意图穿插检测（Phase 7）：当前规划步骤进行中（等待输入/执行/确认），
     * 判断用户输入是否为穿插进入的新意图：
     * - JSON（选项卡/确认按钮点击协议）一定是当前步骤的回复 → 不穿插；
     * - 卡片点击协议文本（含"公司：xx"或 18 位统一信用代码）是对当前步骤询问的回复
     *   （候选企业选择器/歧义选项点击发送的协议文本），→ 不穿插；
     * - 以穿插标记词开头（如"顺便帮我查下华为的融资"）→ 穿插；
     * - 命中其他技能的关键词（如当前在等风险企业输入，用户却问"查下融资情况"）→ 穿插；
     *   （当前为 query_* 同族技能时，命中其他 query_* 同族技能不视为穿插：
     *   CompanyQueryCard 歧义选项点击发送"帮我查一下{企业名}{功能词}"即属此类）；
     * - 仅命中当前步骤技能或未命中任何技能 → 视为对当前步骤的补充回复（不穿插，走原参数合并逻辑）。
     * <p>
     * suppressSkillHit=true（当前步骤等待模糊匹配选择 needsInput）时跳过技能关键词判定：
     * 候选卡片（RiskCheckCard/InformationCheckCard/CompanyQueryCard 等）点击发送的协议文本
     * 多数不含信用代码/"公司："（如"帮我核实{企业名}的信息"、"帮我查一下{企业名}{功能词}"），
     * 若命中其他技能关键词会被误判为穿插——needsInput 状态即代表当前步骤正等待用户选择候选，
     * 此时输入一律视为对当前步骤的回复（仍保留显式穿插标记），不做技能命中穿插判定。
     */
    public boolean isInterleavingIntent(String currentSkill, String text) {
        return isInterleavingIntent(currentSkill, text, false);
    }

    public boolean isInterleavingIntent(String currentSkill, String text, boolean suppressSkillHit) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return false;
        if (INTERLEAVE_MARKER.matcher(trimmed).find()) {
            log.info("Interleaving marker detected in '{}' while plan step '{}' in progress", trimmed, currentSkill);
            return true;
        }
        // 卡片点击协议豁免：候选企业选择器（"公司：xx\n统一信用代码：18位码"）与歧义选项点击
        // （"查询统一信用代码为xxx的客户的风险"）发送的文本是对当前步骤询问的回复（选择企业/确认主体），
        // 不是穿插进入的新意图——含"公司："或 18 位信用代码即视为对当前步骤的企业选择回复
        if (COMPANY_PATTERN.matcher(trimmed).find() || CREDIT_CODE_PATTERN.matcher(trimmed).find()) {
            log.info("Card-click protocol reply detected in '{}', treated as reply to current plan step", trimmed);
            return false;
        }
        // 当前步骤等待模糊匹配选择（needsInput）：点击候选/补充输入都是对该步骤询问的回复，
        // 不再做技能关键词穿插判定——显式穿插标记已在上面处理（INTERLEAVE_MARKER 仍视为穿插）
        if (suppressSkillHit) {
            log.info("Plan step '{}' waiting fuzzy-match input, treating '{}' as reply to current step", currentSkill, trimmed);
            return false;
        }
        Set<String> hit = skillRegistry.matchSkillsByText(trimmed);
        if (currentSkill != null && !currentSkill.isEmpty()) {
            hit.remove(currentSkill);
        }
        // 同族查询技能（query_*）间命中不视为穿插：CompanyQueryCard 歧义选项点击发送
        // "帮我查一下{企业名}{功能词}"（不含信用代码）会命中同族 query_* 技能关键词，
        // 这是对当前企业查询任务的补充确认（选企业），而非穿插新意图；真正的穿插有标记词或命中异族技能
        if (currentSkill != null && currentSkill.startsWith("query_")) {
            hit.removeIf(s -> s.startsWith("query_"));
        }
        if (!hit.isEmpty()) {
            log.info("Interleaving intent detected while plan step '{}' in progress, text hits other skills: {}",
                    currentSkill, hit);
            return true;
        }
        return false;
    }

    /**
     * 意图穿插恢复询问：穿插的新意图处理完成后调用。若存在挂起的旧规划且当前无其他占用状态
     * （无激活规划/确认/待处理技能/待澄清），则<b>不自动恢复</b>，而是发送 resume_confirm 确认卡片，
     * 询问用户是否需要回到穿插进来前的那一步继续旧规划（resumeConfirming 状态由 ChatController
     * 主流程拦截用户回复）：
     * - 用户确认回到 → confirmResume：挂起时确认阶段重发步骤确认卡片，等待输入/执行阶段重跑当前步骤；
     * - 用户拒绝/其他输入 → rejectResume/丢弃挂起规划，旧任务结束。
     *
     * @return 确认卡片事件流；无需询问时返回空流
     */
    public Flux<String> resumePlanIfSuspended(String convId, SkillInvoker skillInvoker) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx == null || !contextMemoryService.hasSuspendedPlan(convId)) return Flux.empty();
        // 有其他占用状态时不询问，避免覆盖进行中的澄清/待处理技能/新规划；
        // 已在 resumeConfirming（上张确认卡片未被答复）时不重复发卡片，防止连续穿插时卡片堆积
        if (ctx.planActive || ctx.planConfirming || ctx.resumeConfirming || ctx.hasPendingSkill()
                || (ctx.pendingClarification != null && !ctx.pendingClarification.isEmpty())) {
            return Flux.empty();
        }
        // 穿插次数已达上限：不再询问用户是否回到穿插前（resume_confirm 卡片），
        // 直接自动恢复挂起断点继续执行，并提示已达到上限
        if (ctx.interleaveCount >= ContextMemoryService.MAX_INTERLEAVE_COUNT) {
            log.info("Interleave count limit reached ({}), auto-resuming suspended plan for conversation {}",
                    ctx.interleaveCount, convId);
            return Flux.just(planProgressEvent(convId,
                    "已达到意图穿插次数上限（" + ContextMemoryService.MAX_INTERLEAVE_COUNT
                            + " 次），已自动回到穿插前的断点继续执行"))
                    .concatWith(autoResumeSuspendedPlan(convId, null, skillInvoker));
        }
        // 穿插的新意图已处理完：不自动执行旧规划的下一步，先由用户决定是否回到穿插前那一步
        log.info("Interleaved task done, asking whether to resume suspended plan for conversation {}", convId);
        contextMemoryService.setResumeConfirming(convId, true);
        ctx = contextMemoryService.get(convId);
        return Flux.just(resumeConfirmEvent(convId, ctx));
    }

    /**
     * 用户确认回到穿插前的那一步（点击 resume_confirm 卡片"回到之前的任务"或输入肯定类文本）：
     * 真正恢复挂起的规划，按挂起时的断点状态继续：
     * - 挂起时处于步骤确认阶段，或报告步骤穿插期间已收尾 → "回到"即继续信号，直接推进执行下一步；
     * - 挂起时处于等待输入/执行阶段 → 重跑当前步骤（等待输入的步骤由技能重新询问）；
     * - 报告步骤仍在外部生成中 → 恢复面板并重发报告生成进度卡片（报告生成停留处），
     *   等前端进度卡轮询 reportComplete 后正常收尾。
     */
    public Flux<String> confirmResume(String convId, String userId, SkillInvoker skillInvoker) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx == null || !contextMemoryService.hasSuspendedPlan(convId)) return Flux.empty();
        contextMemoryService.setResumeConfirming(convId, false);
        return doResumeSuspended(convId, userId, skillInvoker, true);
    }

    /**
     * 意图穿插次数达上限时自动恢复挂起的规划断点（不询问用户，跳过 resume_confirm）：
     * 与 confirmResume 恢复逻辑一致，区别在于挂起时处于步骤确认阶段的场景——用户尚未答复
     * 确认卡，不能擅自推进下一步，改为重发步骤确认卡等待用户决定。
     *
     * @param userId 可能为 null（从 resumePlanIfSuspended 链式触发时无 userId 上下文），
     *               仅报告步骤外部生成且无 report_id 的兜底卡片重发需要，null 时跳过该兜底
     */
    public Flux<String> autoResumeSuspendedPlan(String convId, String userId, SkillInvoker skillInvoker) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx == null || !contextMemoryService.hasSuspendedPlan(convId)) return Flux.empty();
        if (ctx.resumeConfirming) {
            // 正常流程穿插完成后处于 resumeConfirming（等待用户答复确认卡），达上限自动恢复时关闭该标记
            contextMemoryService.setResumeConfirming(convId, false);
        }
        return doResumeSuspended(convId, userId, skillInvoker, false);
    }

    /**
     * 恢复挂起规划断点的公共逻辑：restoreSuspendedPlan 弹出栈顶快照回填 pendingPlan 后，
     * 按挂起时的断点状态继续执行。confirmedByUser 区分两种触发来源：
     * - true（confirmResume）：用户已明确确认"回到之前的任务"，确认阶段直接推进下一步；
     * - false（autoResumeSuspendedPlan，穿插次数达上限）：用户尚未答复确认卡，确认阶段
     *   重发步骤确认卡等待用户决定，其余分支行为一致。
     */
    private Flux<String> doResumeSuspended(String convId, String userId, SkillInvoker skillInvoker,
                                           boolean confirmedByUser) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        ContextMemoryService.SuspendFrame frame = ctx.suspendStack.peek();
        if (frame == null) return Flux.empty();
        boolean wasConfirming = frame.confirming;
        int resumeIdx = frame.index + 1;
        int total = frame.steps.size();
        // 穿插期间报告步骤可能已收尾（reportComplete 穿透挂起快照标记 DONE/FAILED）或仍在生成
        // （WAITING_EXTERNAL）：恢复时都不重跑 generate_report（重跑会再次返回模板/编辑页），
        // 按状态分支——DONE 视为确认阶段重发下一步确认卡；WAITING_EXTERNAL 仅恢复面板继续等待
        boolean suspendedDone = false;
        boolean suspendedWaitingExternal = false;
        if (frame.index >= 0 && frame.index < frame.steps.size()) {
            ContextMemoryService.PlanStep sp = frame.steps.get(frame.index);
            suspendedDone = sp.status == ContextMemoryService.PlanStatus.DONE;
            suspendedWaitingExternal = sp.status == ContextMemoryService.PlanStatus.WAITING_EXTERNAL;
        }
        contextMemoryService.restoreSuspendedPlan(convId);
        ctx = contextMemoryService.get(convId);
        log.info("Resuming suspended plan for conversation {} at step {}/{} (wasConfirming={}, confirmedByUser={})",
                convId, resumeIdx, total, wasConfirming, confirmedByUser);
        // 挂起时处于步骤确认阶段，或报告步骤穿插期间已完成收尾：
        // - 用户确认回到（confirmedByUser）：已是明确的继续信号 → 不再重发确认卡，直接推进执行下一步
        //   （延续穿插前规划；挂起时处于确认阶段 restoreSuspendedPlan 已恢复 planConfirming=true，
        //   必须先关闭确认标记再推进，否则步骤执行期间 planConfirming 残留，用户下一条消息会被
        //   handlePlanConfirmReply 误拦截；advancePlan 使 planIndex 指向待执行的下一步（resumeIdx））；
        // - 自动恢复（穿插次数达上限）：用户尚未答复确认卡 → 重发步骤确认卡等待用户决定，不擅自推进
        if ((wasConfirming || suspendedDone) && resumeIdx < total) {
            contextMemoryService.setPlanConfirming(convId, false);
            if (confirmedByUser) {
                contextMemoryService.advancePlan(convId);
                log.info("Resumed plan from confirmation stage, auto-advancing to step {}/{}", resumeIdx, total);
                return Flux.just(planStatusEvent(convId),
                        planProgressEvent(convId,
                                "好的，已回到之前的任务规划，继续执行第 " + resumeIdx + "/" + total + " 步"))
                        .concatWith(runPlan(convId, skillInvoker));
            }
            log.info("Auto-resumed plan from confirmation stage, re-sending step confirm card {}/{}",
                    resumeIdx, total);
            return Flux.just(planStatusEvent(convId),
                    planProgressEvent(convId,
                            "已达到意图穿插次数上限，已自动回到穿插前的任务，请确认是否继续下一步"))
                    .concatWith(stepDoneAndConfirm(convId, skillInvoker));
        }
        // 防御：确认阶段/已收尾但已无下一步（穿插期间报告完成穿透标记 DONE 且报告是最后一步时
        // 可达——恢复后无需重跑，直接收尾当前穿插规划）
        if (wasConfirming || suspendedDone) {
            log.warn("Resumed but no next step, finishing plan");
            String summaryText = buildPlanSummary(ctx);
            // 最终状态快照（全部步骤 DONE + 汇总文本）必须在 clearPendingPlan 之前发出
            Flux<String> tail = Flux.just(planStatusEvent(convId, summaryText));
            contextMemoryService.clearPendingPlan(convId);
            // 嵌套穿插：本规划收尾后若仍有外层挂起（多层嵌套时恢复中间层且该层已收尾），
            // 立即断点再续询问外层挂起规划（与 stepDoneAndConfirm 收尾逻辑一致，否则
            // 外层挂起得不到恢复确认卡，要等用户下一条消息才触发）
            Flux<String> resume = resumePlanIfSuspended(convId, skillInvoker);
            return tail.concatWith(resume);
        }
        // 报告步骤仍在外部生成中：恢复面板（active=true）与提示，不重跑步骤；
        // 同时重发报告生成进度卡片（report_generate_result → 前端渲染 ProgressCard 自动轮询状态），
        // 让用户回到报告生成的停留处查看/处理；等前端进度卡轮询到报告生成完成后
        // reportComplete 正常收尾（标 DONE 并发下一步确认卡）
        if (suspendedWaitingExternal) {
            Flux<String> flow = Flux.just(planStatusEvent(convId),
                    planProgressEvent(convId,
                            "好的，已回到穿插前的位置。尽调报告仍在生成中，生成完成后可继续下一步"));
            String reportId = resolveSuspendedReportId(convId);
            if (reportId != null && !reportId.isEmpty()) {
                // 重发报告生成进度卡片（前端渲染 ProgressCard 轮询真实状态，含失败态）
                flow = flow.concatWith(Flux.just(reportGenerateProgressEvent(convId, reportId)));
            } else if (userId != null && !userId.isEmpty()) {
                // 兜底：连最新任务都解析不到（会话无报告任务记录，如 H5 旧标签页生成时未携带
                // conversationId、或任务从未创建）时不回退为纯文本，而是重发报告生成步骤卡片
                // （已选模板 → redirect 跳转卡；否则 → 模板选择卡），让用户回到穿插前的
                // 报告生成停留处重新操作（重新打开的 H5 携带 conversationId，任务可正常关联）
                flow = flow.concatWith(Flux.just(reportGenerateResumeFallbackEvent(convId, userId)));
            }
            return flow;
        }
        // 挂起时处于等待输入/执行阶段：先发状态快照恢复面板（active=true，面板重新出现），
        // 再重跑当前步骤（等待输入的步骤由技能重新询问；runPlan 内部会再发一次 RUNNING 态快照，同 id 原地更新）
        return Flux.just(planStatusEvent(convId),
                planProgressEvent(convId,
                        "好的，已回到之前的任务规划，继续执行第 " + resumeIdx + "/" + total + " 步"))
                .concatWith(runPlan(convId, skillInvoker));
    }

    /**
     * 解析挂起报告步骤关联的 report_id，用于恢复时重发报告生成进度卡片：
     * 优先取恢复后当前步骤 params（穿插期间 reportComplete 穿透标记时写回），
     * 兜底查报告任务存储中该会话的报告任务（报告生成中穿插时 report_id 尚未写回步骤，
     * 需从任务存储按会话关联取最新一个）。
     */
    private String resolveSuspendedReportId(String convId) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx != null && ctx.planActive && ctx.planIndex >= 0 && ctx.planIndex < ctx.pendingPlan.size()) {
            Object rid = ctx.pendingPlan.get(ctx.planIndex).params.get("report_id");
            if (rid != null && !String.valueOf(rid).isBlank()) {
                return String.valueOf(rid);
            }
        }
        String taskReportId = reportTaskStore.getAllTasksByConversation(convId).stream()
                .max(Comparator.comparing(ReportTaskStore.ReportTask::getCreatedAt))
                .map(ReportTaskStore.ReportTask::getReportId)
                .orElse(null);
        if (taskReportId == null) {
            // 双数据源均未命中：任务存储中该会话无报告任务（任务 conversationId 为空或从未创建），
            // 恢复时将降级重发报告生成步骤卡片（模板选择/跳转编辑页）
            log.warn("会话 {} 未解析到关联报告任务（步骤 params 无 report_id 且任务存储无该会话任务），"
                    + "恢复时降级重发报告生成步骤卡片", convId);
        }
        return taskReportId;
    }

    /**
     * 报告生成进度卡兜底事件：会话解析不到关联报告任务时，按当前报告步骤 params 状态重发对应卡片，
     * 让用户回到穿插前的报告生成停留处（前端 ReportGenerateCard 支持渲染）：
     * - params 已含 template_id（协调器提取到模板，技能已返回跳转编辑页）→ 重发 redirect 跳转卡；
     * - 否则（模板选择在前端本地完成，步骤 params 通常无 template_id）→ 重发模板选择卡 stage=templates。
     * 重新操作打开的 H5 会携带 conversationId，此后穿插恢复可正常解析到 report_id 重发进度卡。
     */
    private String reportGenerateResumeFallbackEvent(String convId, String userId) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
            if (ctx != null && ctx.planActive && ctx.planIndex >= 0 && ctx.planIndex < ctx.pendingPlan.size()) {
                params = ctx.pendingPlan.get(ctx.planIndex).params;
            }
            String templateId = params.get("template_id") == null
                    ? "" : String.valueOf(params.get("template_id")).trim();
            String organization = params.get("organization") == null
                    ? "" : String.valueOf(params.get("organization"));
            // 机构隔离兜底：模板选择在前端本地完成（点击模板直接生成跳转卡，不经协调器），
            // 步骤 params 通常无 organization；与 ReportGenerateSkill.handle 一致，为空时从用户机构兜底，
            // 避免恢复时 showTemplatesFor("") 不过滤返回全部机构模板
            if (organization == null || organization.isEmpty()) {
                organization = userStoreService.getUser(userId)
                        .map(u -> u.getOrDefault("bank_institution", ""))
                        .orElse("");
                if (organization == null || organization.isEmpty()) {
                    log.warn("会话 {} 恢复报告生成时未解析到机构（params 与用户机构均为空），模板不过滤", convId);
                }
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "report_generate_result");
            // action=result 为前端 ChatMessage 渲染结构化卡片的分发条件（与技能 result 事件一致）
            event.put("action", "result");
            event.put("_skill_name", "generate_report");
            if (!templateId.isEmpty()) {
                // 已选过模板：重发"跳转编辑页"卡片（前端 RedirectCard），点击后新标签页携带 conversationId
                Map<String, Object> template = reportGenerateSkill.findTemplateFor(templateId);
                event.put("stage", "redirect");
                event.put("template_id", templateId);
                event.put("template_name", template == null
                        ? params.getOrDefault("template_name", "") : template.getOrDefault("name", ""));
                event.put("template_icon", template == null
                        ? params.getOrDefault("template_icon", "📄") : template.getOrDefault("icon", "📄"));
                event.put("message", "请在报告编辑页面中上传附件并生成报告");
            } else {
                // 未选模板：重发"选择模板"卡片（前端 TemplateGrid），回到报告生成第一步
                Map<String, Object> tplResp = reportGenerateSkill.showTemplatesFor(organization);
                event.put("stage", "templates");
                if (tplResp.get("templates") instanceof List<?> templates) {
                    event.put("templates", templates);
                }
                event.put("organization", organization);
                event.put("message", "请选择需要生成的报告模板");
            }
            if (convId != null) {
                event.put("conversation_id", convId);
            }
            log.warn("会话 {} 恢复时降级重发报告生成{}卡片（template_id={}）", convId,
                    templateId.isEmpty() ? "模板选择" : "跳转编辑页", templateId);
            return mapper.writeValueAsString(event) + "\n\n";
        } catch (Exception e) {
            return "{\"type\":\"report_generate_result\",\"action\":\"result\","
                    + "\"_skill_name\":\"generate_report\",\"stage\":\"templates\"}\n\n";
        }
    }

    /** 构造 report_generate_result SSE 事件（报告生成进度卡：前端渲染 ProgressCard 轮询生成状态） */
    private String reportGenerateProgressEvent(String convId, String reportId) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "report_generate_result");
            // action=result 为前端 ChatMessage 渲染结构化卡片的分发条件（与技能 result 事件一致）
            event.put("action", "result");
            event.put("_skill_name", "generate_report");
            event.put("stage", "progress");
            event.put("report_id", reportId);
            if (convId != null) {
                event.put("conversation_id", convId);
            }
            return mapper.writeValueAsString(event) + "\n\n";
        } catch (Exception e) {
            return "{\"type\":\"report_generate_result\",\"action\":\"result\",\"_skill_name\":\"generate_report\",\"stage\":\"progress\"}\n\n";
        }
    }

    /**
     * 用户选择不恢复穿插前的任务（点击 resume_confirm 卡片"不需要"或输入否定类文本）：
     * 丢弃挂起的规划，旧任务结束，穿插任务的成果保留在对话中。
     */
    public Flux<String> rejectResume(String convId) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx == null || !contextMemoryService.hasSuspendedPlan(convId)) return Flux.empty();
        contextMemoryService.setResumeConfirming(convId, false);
        contextMemoryService.discardSuspendedPlan(convId);
        log.info("User chose not to resume suspended plan for conversation {}", convId);
        // 被丢弃层规划已收尾：状态快照 steps 为空（active=false）→ 前端据此移除规划面板；
        // 结束反馈文案保留为对话式文本（穿插任务的成果已在对话中可见）
        Flux<String> flow = Flux.just(planStatusEvent(convId),
                planProgressEvent(convId,
                        "好的，穿插前的任务规划已结束。如需继续其他任务，请直接告诉我。"));
        // 嵌套穿插：栈中仍有外层挂起 → 继续弹下一层恢复确认卡（逐层回退，由用户逐层主导）
        ctx = contextMemoryService.get(convId);
        if (contextMemoryService.hasSuspendedPlan(convId)) {
            contextMemoryService.setResumeConfirming(convId, true);
            ctx = contextMemoryService.get(convId);
            flow = flow.concatWith(Flux.just(resumeConfirmEvent(convId, ctx)));
        }
        return flow;
    }

    /** 构造 resume_confirm SSE 事件（穿插任务完成后询问是否回到穿插前那一步） */
    private String resumeConfirmEvent(String convId, ContextMemoryService.ConversationContext ctx) {
        try {
            ContextMemoryService.SuspendFrame frame = ctx.suspendStack.peek();
            if (frame == null) {
                return "{\"type\":\"resume_confirm\",\"content\":\"是否需要回到穿插进来前的那一步？\"}\n\n";
            }
            int total = frame.steps.size();
            boolean wasConfirming = frame.confirming;
            // 展示"回到哪一步"：确认阶段 → 待确认的下一步；执行/等待输入阶段 → 挂起的当前步
            int stepIdx = wasConfirming ? Math.min(frame.index + 1, total - 1) : frame.index;
            ContextMemoryService.PlanStep step = frame.steps.get(stepIdx);
            boolean isChat = "chat".equals(step.skill);
            String displayName = SkillRegistry.displayName(step.skill);
            Object company = step.params.get("company_name");
            String companyStr = company == null ? "" : String.valueOf(company);
            String stepDesc = displayName;
            if (!isChat && companyStr != null && !companyStr.isBlank()) {
                stepDesc += "（" + companyStr + "）";
            }
            String content = "已为您完成穿插的任务。是否需要回到穿插进来前的那一步（第 " + (stepIdx + 1)
                    + "/" + total + " 步：" + stepDesc + "），继续之前的任务规划？";
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "resume_confirm");
            event.put("content", content);
            event.put("step_index", stepIdx + 1);
            event.put("total_steps", total);
            event.put("step_desc", stepDesc);
            if (convId != null) {
                event.put("conversation_id", convId);
            }
            return mapper.writeValueAsString(event) + "\n\n";
        } catch (Exception e) {
            return "{\"type\":\"resume_confirm\",\"content\":\"是否回到穿插进来前的那一步？\"}\n\n";
        }
    }

    /**
     * 构造 resume_confirm 事件 JSON（HTTP 收尾路径复用）：穿插的新规划若以报告步骤收尾，
     * 由 report-complete 响应返回恢复确认卡数据（不经 SSE 流无法即时推送），无挂起规划时返回 null。
     */
    public String resumeConfirmEventJson(String convId) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx == null || !contextMemoryService.hasSuspendedPlan(convId)) return null;
        return resumeConfirmEvent(convId, ctx);
    }
}
