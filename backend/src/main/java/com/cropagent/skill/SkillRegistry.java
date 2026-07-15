package com.cropagent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    public void register(Skill skill) {
        skills.put(skill.getName(), skill);
        log.info("Registered skill: {}", skill.getName());
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
