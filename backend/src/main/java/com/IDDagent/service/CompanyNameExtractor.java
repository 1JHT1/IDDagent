package com.IDDagent.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企业名称提取公共工具类。
 *
 * 统一收敛企业名称提取的三条入口：
 *  - CoordinatorService 直接参数提取（规则式提取）
 *  - ChatController 对 LLM 提取结果的守卫过滤
 *  - 各技能 _user_input 的清洗（RiskCheckSkill / InformationCheckSkill / CompanyQuerySkill / HistoricalDDQuerySkill）
 *
 * 此前各入口各自维护词表与清洗链，修复不同步导致"连环 bug"（如"个企业"被当企业名硬匹配、
 * 技能内清洗前缀正则匹配不到脏值一路到底）。所有词表与规则集中在此处，保证行为唯一。
 */
public final class CompanyNameExtractor {

    private CompanyNameExtractor() {
    }

    // ============================================================
    // 词表
    // ============================================================

    /** 企业名称前常见的介词/引导词（用于提取时清除前缀，"看"处理"看XX企业"开头） */
    private static final Pattern LEADING_PREPS = Pattern.compile("^(对|关于|针对|对于|帮|帮忙|给|请|把|被|让|看)[\\s]*");

    /** 通用查询对象后缀：清除尾部"信息/资料/情况/报告"等查询词（如"核实小米公司的信息" → "小米公司"）。
     *  组合后缀（"基本信息/详细情况"等）整体剥除，避免单条目"信息"把"基本信息"剥成残留"基本" */
    private static final Pattern QUERY_OBJECT_SUFFIX = Pattern.compile(
            "(的信息|基本信息|基本情况|基本资料|详细信息|详细情况|详细资料|最新信息|最新情况|完整信息|完整情况"
                    + "|资料|情况|详情|状况|尽调报告|报告|尽调|信息)$");

    /** 时间描述词：清除"近一年/最近3个月/过去两年/半年/2024年1月"等，防止残留进企业名 */
    private static final Pattern TIME_DESC_PATTERN = Pattern.compile(
            "(?:近|最近|过去)\\s*[0-9一二两三四五六七八九十]+\\s*个?月"
                    + "|(?:近|最近|过去)\\s*[0-9一二两三四五六七八九十]+\\s*年"
                    + "|[0-9一二两三四五六七八九十]+\\s*年内"
                    + "|[上下]?半年|季度"
                    + "|\\d{4}-\\d{2}-\\d{2}"
                    + "|\\d{4}\\s*年\\s*\\d{1,2}\\s*月(?:\\s*\\d{1,2}\\s*日?)?");

    /** 企业名称定位标记："企业是""公司名称是"等标记之后的文本优先作为企业名候选 */
    private static final Pattern COMPANY_NAME_MARKER = Pattern.compile("(?:企业|公司)(?:名称)?(?:是|为|叫|：|:)\\s*");

    /** 定位标记前缀："企业是/公司为"出现在文本开头时（去除式清洗残留），整段清除 */
    private static final Pattern COMPANY_MARKER_PREFIX = Pattern.compile("^(?:企业|公司)(?:名称)?(?:是|为|叫|：|:)\\s*");

    /** 疑问词守卫：提取结果含疑问词视为疑问句（如"云禾科技是什么"），不是真实企业名，命中返回提取失败 */
    private static final List<String> MARKER_TAIL_GUARD = List.of(
            "什么", "怎么", "如何", "为什么", "多少", "是否", "吗", "呢");

    /** 残留意愿/动作词：清洗后仍包含则视为提取失败，回退 LLM 提取或保持旧值。
     *  覆盖主谓意愿表达（"我需要完整报告"→清洗残留"我需要完整"）与无主语意愿（"需要一份"），
     *  防止"我需要完整""需要一份"被当作企业名硬匹配 */
    private static final List<String> RESIDUAL_ACTION_WORDS = List.of(
            "我想要", "我需要", "我要求",
            "我想", "我要", "需要", "打算", "麻烦", "帮我", "给我", "帮忙");

    /** 查询性质尾部残留词：清洗后仍以"基本/详细/最新/完整"结尾，说明"基本信息/详细情况"等组合后缀被部分剥离。
     *  真实企业名不以这些词结尾（以"有限公司/集团/厂/中心"等结尾），命中视为提取失败 */
    private static final List<String> QUERY_TAIL_RESIDUAL = List.of("基本", "详细", "最新", "完整");

    /** 直接提取时用于清理的常见动作/语气词（按长度降序使用）。
     *  含口语动词短语（"识别一下"：防止"帮我识别一下风险"残留"识别"）
     *  与量词短语（"给我一份"：防止"给我一份风险报告"因"给"被单独删除而残留"我一 份"） */
    private static final List<String> STRIP_WORDS = List.of(
            "看看有没有", "识别一下", "给我一份",
            "帮我", "请", "给", "一下", "看看",
            "查一下", "查询", "查", "查看", "做一下", "进行", "做", "做个", "做一个",
            "有没有", "我想", "想做", "打算",
            "的", "了", "吧", "啊", "呢", "吗",
            "，", "。", "！", "？", "、"
    );

    /** 指代代词"它"：仅"它"后紧跟标点/空白/"的"/结尾时删除（"它的风险"→"风险"），
     *  限位避免误伤"它山科技"类含"它"的真实企业名 */
    private static final Pattern PRONOUN_IT = Pattern.compile("它(?=[\\s，。！？、的]|$)");

    /** 上下文指代词（如"这家公司""该公司"）：不可能是真实企业名称，命中时视为指代前文提到的企业 */
    private static final List<String> CONTEXT_REFERENCE_WORDS = List.of(
            "这家公司", "这个公司", "这家企业", "这个企业", "该公司", "本公司",
            "这家", "上次", "刚才那家", "上一家", "前面那家", "之前那家");

    /**
     * 泛企业指称模式：整体为"企业/公司"或其泛指变体（"个企业""这个企业""做个企业""某公司"等），
     * 不指向任何具体企业，不应作为企业名提取/匹配。
     * 用整体正则而非 contains，避免误伤含"公司"的真实企业名（如"云禾科技有限公司"）。
     */
    private static final Pattern GENERIC_COMPANY_PATTERN = Pattern.compile(
            "^(?:做一个|做个|个|这个|那个|某|某个|一个|一家|这|那)?(?:企业|公司)$");

    /** 统一信用代码字段："统一信用代码:XXX" 形式（前端卡片点击格式，代码可为任意长度的测试占位码） */
    private static final Pattern CREDIT_CODE_FIELD = Pattern.compile("统一信用代码[:：]\\s*([0-9A-Za-z]+)");

    /** 裸统一信用代码（18 位标准格式，用户直接粘贴场景） */
    private static final Pattern CREDIT_CODE_PATTERN = Pattern.compile("[0-9A-Za-z]{18}");

    // ============================================================
    // 守卫
    // ============================================================

    /** 判断文本是否包含上下文指代词（用户用"这家公司"等指代前文企业） */
    public static boolean isContextReference(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String w : CONTEXT_REFERENCE_WORDS) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    /** 判断文本是否为泛企业指称（非真实企业名） */
    public static boolean isGenericCompanyReference(String text) {
        if (text == null) return false;
        return GENERIC_COMPANY_PATTERN.matcher(text.trim()).matches();
    }

    /**
     * 企业名有效性总校验：长度 2~30、非疑问句（"云禾科技是什么"）、非上下文指代词、
     * 非泛企业指称、无残留意愿/动作词、无查询性质尾部残留词。用于提取结果与 LLM 提取结果的统一守卫。
     */
    public static boolean isValidCompanyName(String name) {
        if (name == null) return false;
        String cleaned = name.trim();
        if (cleaned.length() < 2 || cleaned.length() > 30) return false;
        if (containsAny(cleaned, MARKER_TAIL_GUARD)) return false;
        if (isContextReference(cleaned)) return false;
        if (isGenericCompanyReference(cleaned)) return false;
        if (endsWithAny(cleaned, QUERY_TAIL_RESIDUAL)) return false;
        return !containsAny(cleaned, RESIDUAL_ACTION_WORDS);
    }

    /**
     * 统一社会信用代码合法性（18 位数字+字母，大小写不敏感）。
     * 用于 LLM 提取结果守卫（如 LLM 误把公司名塞入 credit_code 参数时识别并清除）。
     */
    public static boolean isValidCreditCode(String code) {
        if (code == null) return false;
        return code.trim().toUpperCase().matches("[0-9A-Z]{18}");
    }

    // ============================================================
    // 提取
    // ============================================================

    /**
     * 从文本中提取统一信用代码。
     * 优先"统一信用代码[:：]XXX"字段形式（前端卡片点击格式），其次 18 位裸代码（用户直接粘贴）。
     * 无代码返回空字符串。
     */
    public static String extractCreditCode(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher field = CREDIT_CODE_FIELD.matcher(text);
        if (field.find()) {
            return field.group(1).toUpperCase();
        }
        Matcher bare = CREDIT_CODE_PATTERN.matcher(text);
        if (bare.find()) {
            return bare.group().toUpperCase();
        }
        return "";
    }

    /**
     * 从用户消息中提取企业名称（统一清洗链，Coordinator 与各技能共用）。
     *
     * 策略一（定位式）：优先识别"企业是/公司名为"等定位标记，取标记之后的文本作为企业名候选；
     * 策略二（去除式）：无定位标记时按固定顺序清洗：
     * 删触发关键词 → 删技能动词 → 删常见语气/动作词 → 删前缀介词 → 删技能/通用查询后缀 → 删时间描述 → 清理标点。
     * 最后经 isValidCompanyName 守卫校验，无效返回 null（调用方回退 LLM 或保持旧值）。
     *
     * @param text            用户原始输入
     * @param verbs           技能触发动词（"|"分隔，可为 null），如 RiskCheck 的"风险预查|风险筛查|查询"
     * @param suffixes        技能查询对象后缀（"|"分隔，可为 null），如"的风险|情况|信息"；清洗链会先删"的"，
     *                        因此带"的"的条目会自动退化为裸形式匹配，调用方只需按直觉提供
     * @param matchedKeywords 触发的意图关键词（可为 null），任意位置删除
     */
    public static String extractCompanyName(String text, String verbs, String suffixes, List<String> matchedKeywords) {
        if (text == null) return null;
        String message = text.trim();
        if (message.isEmpty()) return null;

        // 策略一：定位标记（"企业是/公司名称是"等）→ 标记后文本作为候选，走同一清洗链；
        // 策略二：无定位标记时按固定顺序清洗全文。两条策略共用 cleanAndGuard，保证清洗行为唯一
        // （此前标记提取只截取不清洗，导致"企业是云禾科技的风险情况"直接返回脏名）
        String markedCandidate = extractAfterCompanyMarker(message);
        if (markedCandidate != null) {
            String cleanedMarked = cleanAndGuard(markedCandidate, verbs, suffixes, matchedKeywords);
            if (cleanedMarked != null) {
                return cleanedMarked;
            }
            // 标记候选清洗失败（如"企业是云禾科技，它是什么情况"含疑问结构）→ 回退全文去除式
        }
        return cleanAndGuard(message, verbs, suffixes, matchedKeywords);
    }

    /**
     * 统一清洗链：删标记前缀 → 删触发关键词 → 删技能动词 → 删指代"它"/动作词 → 删前缀介词
     * → 删技能/通用查询后缀 → 删时间描述 → 清理空白标点 → 守卫校验。
     */
    private static String cleanAndGuard(String text, String verbs, String suffixes, List<String> matchedKeywords) {
        String cleaned = text;

        // 0. 清除定位标记前缀残留（去除式清洗时，"企业是云禾科技"开头的"企业是"整段清除）
        cleaned = COMPANY_MARKER_PREFIX.matcher(cleaned).replaceAll("");

        // 1. 去除匹配的触发关键词（任意位置）
        if (matchedKeywords != null) {
            for (String kw : matchedKeywords) {
                if (kw != null && !kw.isEmpty()) {
                    cleaned = cleaned.replace(kw, "");
                }
            }
        }

        // 2. 去除技能动词（按长度降序，避免"查"先删破坏"查一下"）
        if (verbs != null && !verbs.isEmpty()) {
            String[] verbArr = verbs.split("\\|");
            Arrays.sort(verbArr, (a, b) -> Integer.compare(b.length(), a.length()));
            for (String v : verbArr) {
                if (!v.isEmpty()) {
                    cleaned = cleaned.replace(v, "");
                }
            }
        }

        // 3. 去除指代"它"（先于"的"删除，保证"它的"整体可删）与常见动作词/语气词（按长度降序，避免子串问题）
        cleaned = PRONOUN_IT.matcher(cleaned).replaceAll("");
        List<String> sortedStrip = new ArrayList<>(STRIP_WORDS);
        sortedStrip.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String word : sortedStrip) {
            cleaned = cleaned.replace(word, "");
        }

        // 4. 清除前缀介词/引导词（如"对云禾科技" → "云禾科技"）
        cleaned = LEADING_PREPS.matcher(cleaned).replaceAll("");

        // 5. 清除查询对象后缀：先技能自定义后缀（更长更具体），后通用后缀（更短更通用），
        //    避免通用后缀先删"信息"破坏"股东信息"等组合后缀的匹配
        cleaned = stripSuffixes(cleaned, suffixes);
        cleaned = QUERY_OBJECT_SUFFIX.matcher(cleaned).replaceAll("");

        // 6. 清除时间描述（如"查询XX近一年的尽调报告" → 去"近一年" → "XX"）
        cleaned = TIME_DESC_PATTERN.matcher(cleaned).replaceAll("");

        // 7. 清理空白和标点
        cleaned = cleaned.replaceAll("[，。！？、\\s]+", " ").trim();

        // 8. 守卫：疑问句 / 上下文指代词 / 泛企业指称 / 残留意愿词 / 长度异常 → 提取失败返回 null
        if (isValidCompanyName(cleaned)) {
            return cleaned;
        }
        return null;
    }

    /** 技能自定义后缀删除（按长度降序，尾部匹配） */
    private static String stripSuffixes(String text, String suffixes) {
        if (suffixes == null || suffixes.isEmpty() || text.isEmpty()) return text;
        String[] sufArr = suffixes.split("\\|");
        Arrays.sort(sufArr, (a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder pattern = new StringBuilder("(?:");
        for (String s : sufArr) {
            if (!s.isEmpty()) {
                pattern.append(Pattern.quote(s)).append("|");
            }
        }
        if (pattern.length() <= 2) return text;
        pattern.setLength(pattern.length() - 1); // 去掉末尾多余的 |
        pattern.append(")$");
        return Pattern.compile(pattern.toString()).matcher(text).replaceAll("");
    }

    /**
     * 定位式提取：取最后一个"企业是/公司名称是"等标记之后的文本作为企业名候选。
     * 仅负责定位与长度粗筛（含疑问词/残留意愿词的 tail 交由统一清洗链守卫拦截），
     * 返回的候选与全文一样走 cleanAndGuard，保证清洗行为唯一。
     */
    private static String extractAfterCompanyMarker(String message) {
        Matcher m = COMPANY_NAME_MARKER.matcher(message);
        int lastEnd = -1;
        while (m.find()) {
            lastEnd = m.end();
        }
        if (lastEnd < 0) return null;

        String tail = message.substring(lastEnd).trim();
        if (tail.isEmpty() || tail.length() > 30) return null;

        // 清理尾部标点（保留完整组织名，技能内部会精确/模糊匹配）
        tail = tail.replaceAll("[，。！？、\\s]+", " ").trim();
        return tail.length() >= 2 ? tail : null;
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    private static boolean endsWithAny(String text, List<String> words) {
        for (String w : words) {
            if (text.endsWith(w)) return true;
        }
        return false;
    }
}
