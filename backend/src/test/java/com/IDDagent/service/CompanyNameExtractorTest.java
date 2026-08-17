package com.IDDagent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompanyNameExtractor 回归测试。
 *
 * 固化历史 bug 场景，防止后续修复引入"连环 bug"：
 *  - 标记提取后不走统一清洗链（"企业是云禾科技的风险情况"返回脏名）
 *  - 疑问句残留被当作企业名硬匹配（"云禾科技是什么情况"）
 *  - 上下文指代（"这家公司"）/ 泛企业指称（"这个企业"）被当作真实企业名
 *  - 指代"它"误删导致误伤含"它"的真实企业名（PRONOUN_IT 限位删除）
 *  - 裸码小写未归一化、非 18 位脏值通过信用代码守卫
 *  - 超长候选（>30 字符）未拦截
 */
class CompanyNameExtractorTest {

    /** 模拟 RiskCheck 技能词表（测试专用，与技能内实际词表解耦） */
    private static final String RISK_VERBS = "风险预查|风险筛查|查询|查一下|查";
    private static final String RISK_SUFFIXES = "的风险情况|风险情况|的风险|情况|风险";
    private static final List<String> RISK_KEYWORDS = List.of("风险");

    /** 模拟 CompanyQuery 技能词表 */
    private static final String QUERY_VERBS = "查询|查一下|查查|了解一下|查|看看|看一下|提供|获取|核实";
    private static final String QUERY_SUFFIXES = "的股东信息|股东信息|的股东|股东|的基本信息|基本信息|的信息|信息|资料|情况";

    private static String riskExtract(String text) {
        return CompanyNameExtractor.extractCompanyName(text, RISK_VERBS, RISK_SUFFIXES, RISK_KEYWORDS);
    }

    private static String queryExtract(String text) {
        return CompanyNameExtractor.extractCompanyName(text, QUERY_VERBS, QUERY_SUFFIXES, null);
    }

    // ============================================================
    // 修复 1：标记提取共用统一清洗链
    // ============================================================

    /** "企业是"标记后的文本必须走清洗链删除"的风险情况"，而不是截取即返回 */
    @Test
    void markedTailIsCleanedThroughCommonChain() {
        assertEquals("云禾科技", riskExtract("请帮我查一下企业是云禾科技的风险情况"));
    }

    /** 标记 + 技能动词 + 指代"它"：删除"它"后剩余"股东"由技能后缀链删除 */
    @Test
    void markedTailWithVerbAndPronounIt() {
        assertEquals("星河", queryExtract("公司是星河，查一下它的股东"));
    }

    /** 标记 + 残留请求短语"帮我查一下它的风险情况"：全链清洗后仅剩企业名 */
    @Test
    void markedTailWithResidualRequestPhrase() {
        assertEquals("云禾科技", riskExtract("企业是云禾科技，帮我查一下它的风险情况"));
    }

    // ============================================================
    // 修复 2：定位标记前缀残留整段清除
    // ============================================================

    /** 无标记候选时的全文去除式清洗：开头的"企业是"整段清除，不留残名 */
    @Test
    void markerPrefixResidualCleared() {
        assertNull(riskExtract("企业是"));
    }

    // ============================================================
    // 守卫：泛企业指称 / 上下文指代 / 疑问句
    // ============================================================

    /** 泛企业指称（"这个企业""做个企业"）不指向具体企业，不得当企业名 */
    @Test
    void genericCompanyReferenceRejected() {
        assertNull(queryExtract("查一下这个企业"));
        assertNull(queryExtract("做个企业"));
    }

    /** 上下文指代（"这家公司"）应交给上下文记忆补全，而非当作企业名硬匹配 */
    @Test
    void contextReferenceRejected() {
        assertNull(riskExtract("这家公司的风险"));
    }

    /** 疑问句残留（清洗后仍含"是什么"）不得当企业名，交给 LLM 意图澄清 */
    @Test
    void questionSentenceRejected() {
        assertNull(riskExtract("云禾科技是什么情况"));
        assertNull(queryExtract("企业是云禾科技，它是什么情况"));
    }

    // ============================================================
    // 清洗链：时间描述 / 查询后缀
    // ============================================================

    /** "近一年"时间描述必须被删除，不能残留在企业名中 */
    @Test
    void timeDescriptionStripped() {
        assertEquals("云禾科技", riskExtract("查询云禾科技近一年的风险"));
    }

    /** 新增口语动词"了解一下""查查"必须整体剥离，不得残留混入企业名 */
    @Test
    void newColloquialVerbsStripped() {
        assertEquals("小米科技", queryExtract("帮我了解一下小米科技"));
        assertEquals("小米科技", queryExtract("小米科技，帮我查查"));
    }

    /** 通用查询后缀"的基本信息"删除，保留完整企业名 */
    @Test
    void querySuffixStripped() {
        assertEquals("北京星河科技有限公司", queryExtract("查询北京星河科技有限公司的基本信息"));
    }

    /** PRONOUN_IT 限位删除：仅删"它"后跟标点/空白/"的"的指代用法，不得误伤"它山科技"类真实企业名 */
    @Test
    void pronounItDoesNotHurtRealCompanyName() {
        assertEquals("它山科技", riskExtract("查一下它山科技的风险"));
    }

    // ============================================================
    // 修复 7：主谓意愿 / 动词量词短语残留不得当企业名
    // ============================================================

    /** "我需要完整报告"清洗后残留"我需要完整"：主谓意愿词残留必须拦截（回退 LLM），不得硬匹配 */
    @Test
    void willingnessResidualRejected() {
        assertNull(riskExtract("风险识别一下，我需要完整报告"));
        assertNull(riskExtract("我需要完整报告"));
        assertNull(riskExtract("我需要"));
    }

    /** 无主语意愿表达："需要一份风险报告"残留"需要一份"必须拦截 */
    @Test
    void needQuantifierResidualRejected() {
        assertNull(riskExtract("需要一份风险报告"));
    }

    /** 动词短语残留："帮我识别一下风险"不得残留"识别"作为企业名 */
    @Test
    void verbPhraseResidualRejected() {
        assertNull(riskExtract("帮我识别一下风险"));
    }

    /** 量词短语残留："给我一份风险报告"不得因"给"被单独删除而残留"我一 份" */
    @Test
    void quantifierPhraseResidualRejected() {
        assertNull(riskExtract("给我一份风险报告"));
    }

    /** 新守卫不得误伤真实企业名："查一下云禾科技的风险"正常提取；"我需要X"结构交由 LLM 精确提取 */
    @Test
    void willingnessGuardDoesNotHurtRealCompanyName() {
        assertEquals("云禾科技", riskExtract("查一下云禾科技的风险"));
        assertNull(riskExtract("我需要云禾科技的风险"));
    }

    // ============================================================
    // 修复 8：查询性质组合后缀不得残留"基本/详细"等修饰词
    // ============================================================

    /** Coordinator 直接提取路径（verbs/suffixes 均为 null，仅通用后缀链）：
     *  "查询一下小米科技有限责任公司的基本信息" 必须提取完整企业名，不得残留"基本" */
    @Test
    void coordinatorDirectExtractNoBasicResidual() {
        assertEquals("小米科技有限责任公司",
                CompanyNameExtractor.extractCompanyName(
                        "查询一下小米科技有限责任公司的基本信息", null, null, List.of("查询")));
    }

    /** 组合后缀整体剥除（含"的"变体与"情况"变体） */
    @Test
    void combinedQuerySuffixStripped() {
        assertEquals("云禾科技",
                CompanyNameExtractor.extractCompanyName("查一下云禾科技的基本情况", null, null, List.of("查一下")));
        assertEquals("云禾科技",
                CompanyNameExtractor.extractCompanyName("查一下云禾科技的基本信息", null, null, List.of("查一下")));
        assertEquals("云禾科技",
                CompanyNameExtractor.extractCompanyName("查一下云禾科技的详细信息", null, null, List.of("查一下")));
    }

    /** 防御守卫：即使组合后缀剥不净，以"基本/详细/最新/完整"结尾的残留也不得通过 isValidCompanyName */
    @Test
    void queryTailResidualRejected() {
        assertFalse(CompanyNameExtractor.isValidCompanyName("小米科技有限责任公司基本"));
        assertFalse(CompanyNameExtractor.isValidCompanyName("云禾科技详细"));
        assertFalse(CompanyNameExtractor.isValidCompanyName("云禾科技最新"));
        assertFalse(CompanyNameExtractor.isValidCompanyName("云禾科技完整"));
    }

    /** 新守卫不得误伤真实企业名：正常企业名结尾不在残留词表内，照常通过 */
    @Test
    void queryTailGuardDoesNotHurtRealCompanyName() {
        assertTrue(CompanyNameExtractor.isValidCompanyName("北京星河科技有限公司"));
        assertEquals("北京星河科技有限公司",
                CompanyNameExtractor.extractCompanyName(
                        "查询北京星河科技有限公司的基本信息", null, null, List.of("查询")));
    }

    // ============================================================
    // 修复 6：长度上限（2 ~ 30）
    // ============================================================

    /** 标记后候选超过 30 字符 → 回退全文去除式 → 长度守卫仍拦截 */
    @Test
    void overLengthCandidateRejected() {
        String longName = "云禾科技".repeat(8); // 32 字符
        assertNull(riskExtract("企业是" + longName));
    }

    // ============================================================
    // 信用代码提取与校验（修复 3：裸码大小写统一）
    // ============================================================

    /** 前端卡片"统一信用代码：XXX"字段提取 */
    @Test
    void creditCodeFieldExtracted() {
        assertEquals("91110108MA01B3XK2P",
                CompanyNameExtractor.extractCreditCode("公司：云禾科技\n统一信用代码：91110108MA01B3XK2P"));
    }

    /** 18 位裸码小写输入 → 统一大写返回 */
    @Test
    void bareCreditCodeLowercaseNormalizedToUpper() {
        assertEquals("91110108MA01B3XK2P",
                CompanyNameExtractor.extractCreditCode("查91110108ma01b3xk2p"));
    }

    /** 无信用代码 → 空字符串 */
    @Test
    void creditCodeAbsentReturnsEmpty() {
        assertEquals("", CompanyNameExtractor.extractCreditCode("查一下云禾科技"));
    }

    /** 18 位数字+字母（大小写不敏感）通过校验；公司名/空值/null 拒绝 */
    @Test
    void creditCodeValidation() {
        assertTrue(CompanyNameExtractor.isValidCreditCode("91110108MA01B3XK2P"));
        assertTrue(CompanyNameExtractor.isValidCreditCode("91110108ma01b3xk2p"));
        assertFalse(CompanyNameExtractor.isValidCreditCode("星河"));
        assertFalse(CompanyNameExtractor.isValidCreditCode(""));
        assertFalse(CompanyNameExtractor.isValidCreditCode(null));
    }

    // ============================================================
    // isValidCompanyName 直接守卫
    // ============================================================

    @Test
    void companyNameValidation() {
        assertTrue(CompanyNameExtractor.isValidCompanyName("云禾科技"));
        assertTrue(CompanyNameExtractor.isValidCompanyName("北京星河科技有限公司"));
        assertFalse(CompanyNameExtractor.isValidCompanyName(null));
        assertFalse(CompanyNameExtractor.isValidCompanyName(""));
        assertFalse(CompanyNameExtractor.isValidCompanyName("云"));
        assertFalse(CompanyNameExtractor.isValidCompanyName("云禾科技是什么"));
        assertFalse(CompanyNameExtractor.isValidCompanyName("这家公司"));
        assertFalse(CompanyNameExtractor.isValidCompanyName("个企业"));
        assertFalse(CompanyNameExtractor.isValidCompanyName("帮我查云禾科技"));
    }
}
