package com.IDDagent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    /** 技能名 → 关键词别名表（模糊意图识别兜底用） */
    private final Map<String, List<String>> keywordAliases = new ConcurrentHashMap<>();

    public void register(Skill skill) {
        register(skill, Collections.emptyList());
    }

    public void register(Skill skill, List<String> aliases) {
        skills.put(skill.getName(), skill);
        if (aliases != null && !aliases.isEmpty()) {
            keywordAliases.put(skill.getName(), List.copyOf(aliases));
        }
        log.info("Registered skill: {} (aliases: {})", skill.getName(), aliases == null ? 0 : aliases.size());
    }

    /**
     * 按关键词别名做本地模糊意图匹配（LLM 意图识别失败/降级时的兜底路由）。
     * 仅做路由命中，不提取参数。
     *
     * @return 命中时返回 {"action":"skill","skill":"...","params":{},"reason":"按模糊匹配命中（置信度xx）"}；
     *         最高分与次高分接近（疑似多意图）或没有任何技能达到阈值时返回 null（交给 LLM/chat 兜底）
     */
    public Map<String, Object> matchByKeyword(String text) {
        if (text == null || text.isEmpty() || keywordAliases.isEmpty()) return null;
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : keywordAliases.entrySet()) {
            double s = IntentMatcher.score(text, entry.getValue());
            if (s >= 0.6) {
                scores.put(entry.getKey(), s);
            }
        }
        if (scores.isEmpty()) return null;

        // 按得分降序取前两名
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        double top = ranked.get(0).getValue();
        if (top < 0.9) return null;
        if (ranked.size() > 1 && ranked.get(1).getValue() >= 0.85) {
            // 两个技能置信度接近，疑似多意图 → 交 LLM 判断
            log.info("Fuzzy match ambiguous between '{}'({}) and '{}'({}), defer to LLM",
                    ranked.get(0).getKey(), String.format("%.2f", top),
                    ranked.get(1).getKey(), String.format("%.2f", ranked.get(1).getValue()));
            return null;
        }

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "skill");
        decision.put("skill", ranked.get(0).getKey());
        decision.put("params", Collections.emptyMap());
        decision.put("reason", String.format("按模糊匹配命中（置信度%.2f）", top));
        log.info("Fuzzy match hit: {} (confidence {})", ranked.get(0).getKey(), String.format("%.2f", top));
        return decision;
    }

    public Skill get(String name) {
        return skills.get(name);
    }

    public String getSkillsPrompt() {
        if (skills.isEmpty()) {
            return "（当前无可用技能）";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Skill skill : skills.values()) {
            sb.append(i).append(". ").append(skill.toPromptDesc()).append("\n");
            i++;
        }
        return sb.toString();
    }

    public List<String> listSkillNames() {
        return new ArrayList<>(skills.keySet());
    }

    /**
     * 按关键词别名返回文本命中的技能名集合（意图穿插检测用，不做置信度阈值过滤）。
     * 只做包含匹配：文本含有某技能任一关键词即计入命中；无关键词注册时返回空集合。
     */
    public Set<String> matchSkillsByText(String text) {
        Set<String> hit = new LinkedHashSet<>();
        if (text == null || text.isEmpty() || keywordAliases.isEmpty()) return hit;
        for (Map.Entry<String, List<String>> entry : keywordAliases.entrySet()) {
            for (String kw : entry.getValue()) {
                if (text.contains(kw)) {
                    hit.add(entry.getKey());
                    break;
                }
            }
        }
        return hit;
    }

    public Map<String, Object> invoke(String name, String userId, Map<String, Object> params) {
        Skill skill = skills.get(name);
        if (skill == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "技能 '" + name + "' 未注册");
            return error;
        }
        try {
            return skill.invoke(userId, params);
        } catch (Exception e) {
            log.error("Skill execution failed: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "技能执行失败: " + e.getMessage());
            return error;
        }
    }
}
