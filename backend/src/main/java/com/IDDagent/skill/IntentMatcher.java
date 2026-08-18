package com.IDDagent.skill;

import java.io.UnsupportedEncodingException;
import java.util.List;

/**
 * 模糊意图匹配工具（纯 Java，无外部依赖）。
 * 用于 LLM 意图识别失败/超时时的本地降级兜底：
 * - 编辑距离（Levenshtein）容忍错别字
 * - 拼音首字母容忍 "fx" → 风险、"gdfx" → 股东风险
 * - 别名打分：包含匹配 +10、编辑距离 ≤1 +6、首字母匹配 +5，按最长别名归一化到 0~1
 */
public final class IntentMatcher {

    private IntentMatcher() {
    }

    /** GB2312 一级汉字区位码分段表（23 段，I/U/V 无独立段） */
    private static final int[] SEC_POS = {
            1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787, 3106,
            3212, 3240, 3301, 3390, 3402, 3631, 3915, 3959, 4001, 4013,
            4131, 4208, 5094
    };
    private static final char[] FIRST_LETTER = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K',
            'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'W',
            'X', 'Y', 'Z'
    };

    /**
     * 计算两个字符串的编辑距离（Levenshtein Distance）。
     * 用于容忍用户输入中的错别字（如"风评"→"风评"偏差 1 个字符内）。
     */
    public static int editDistance(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? 0 : b.length();
        if (b == null || b.isEmpty()) return a.length();
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /**
     * 单个汉字转拼音首字母（GB2312 区位码查表）。
     * 非汉字字母/数字原样返回（小写字母转大写），无法识别的字符原样返回。
     */
    public static char pinyinFirstLetter(char ch) {
        if (ch >= 'a' && ch <= 'z') return (char) (ch - 32);
        if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) return ch;
        // GB2312 一级汉字范围外（生僻字、符号）原样返回，不强行映射
        if (ch < 0x4E00 || ch > 0x9FA5) return ch;
        try {
            byte[] gb = String.valueOf(ch).getBytes("GB2312");
            if (gb == null || gb.length != 2) return ch;
            int high = gb[0] & 0xFF;
            int low = gb[1] & 0xFF;
            if (high < 0xB0 || high > 0xF7 || low < 0xA1 || low > 0xFE) return ch;
            int sec = (high - 0xA0) * 100 + (low - 0xA0);
            for (int i = 0; i < SEC_POS.length; i++) {
                if (sec < SEC_POS[i]) {
                    return i == 0 ? 'A' : FIRST_LETTER[i - 1];
                }
            }
            return 'Z';
        } catch (UnsupportedEncodingException e) {
            return ch;
        }
    }

    /**
     * 提取文本的拼音首字母串。
     * 仅保留 A-Z 与 0-9（标点、空格等被丢弃），如 "fx" → "FX"。
     */
    public static String pinyinInitials(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = pinyinFirstLetter(text.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 计算用户文本对一组别名的模糊匹配得分（0~1）。
     * 归一化：去掉标点空白并转小写后，
     * - 包含匹配 +10 + min(5, 别名长*0.5)
     * - 滑窗编辑距离 ≤1 的错别字 +6
     * - 拼音首字母命中 +5
     * 按理论最高分（10 + min(5, maxLen*0.5) + 5）归一化。
     */
    public static double score(String userText, List<String> aliases) {
        if (userText == null || userText.isEmpty() || aliases == null || aliases.isEmpty()) return 0;
        String norm = normalize(userText);
        if (norm.isEmpty()) return 0;

        double total = 0;
        int maxLen = 0;
        String normInitials = pinyinInitials(norm);

        for (String alias : aliases) {
            if (alias == null || alias.isEmpty()) continue;
            String a = normalize(alias);
            if (a.isEmpty()) continue;
            maxLen = Math.max(maxLen, a.length());

            if (norm.contains(a)) {
                total += 10 + Math.min(5, a.length() * 0.5);
            } else if (containsFuzzy(norm, a)) {
                total += 6;
            } else {
                // 拼音首字母：用户输入 "fx" / "911fx" 命中 "风险" 的首字母
                String aliasInitials = pinyinInitials(a);
                if (!aliasInitials.isEmpty()
                        && (norm.contains(aliasInitials) || normInitials.contains(aliasInitials))) {
                    total += 5;
                }
            }
        }
        if (total == 0) return 0;
        double maxScore = 10 + Math.min(5, maxLen * 0.5) + 5;
        return Math.min(1.0, total / maxScore);
    }

    /**
     * 滑窗检测：文本中是否存在与别名编辑距离 ≤1 的子串（容忍 1 个错别字）。
     */
    private static boolean containsFuzzy(String text, String alias) {
        if (alias.length() < 2) return false;
        for (int i = 0; i <= text.length() - alias.length() + 1; i++) {
            int end = Math.min(text.length(), i + alias.length() + 1);
            if (end - i < alias.length() - 1) continue;
            if (editDistance(text.substring(i, end), alias) <= 1) return true;
        }
        return false;
    }

    /** 归一化：仅保留字母与数字（去空白、标点、中文标点），转小写 */
    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
