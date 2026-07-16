package com.IDDagent.skill;

import java.util.Map;
import java.util.function.BiFunction;

public class Skill {
    private final String name;
    private final String description;
    private final BiFunction<String, Map<String, Object>, Map<String, Object>> handler;
    private final Map<String, SkillParam> parameters;

    public Skill(String name, String description,
                 BiFunction<String, Map<String, Object>, Map<String, Object>> handler,
                 Map<String, SkillParam> parameters) {
        this.name = name;
        this.description = description;
        this.handler = handler;
        this.parameters = parameters;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, SkillParam> getParameters() { return parameters; }

    public Map<String, Object> invoke(String userId, Map<String, Object> params) {
        return handler.apply(userId, params);
    }

    public String toPromptDesc() {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(name).append("**: ").append(description);
        if (parameters != null && !parameters.isEmpty()) {
            sb.append("\n  参数: ");
            boolean first = true;
            for (var entry : parameters.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(entry.getKey()).append(" (示例: ").append(entry.getValue().getExample()).append(")");
            }
        }
        return sb.toString();
    }

    public static class SkillParam {
        private String type;
        private String description;
        private boolean required;
        private String example;

        public SkillParam(String type, String description, boolean required, String example) {
            this.type = type;
            this.description = description;
            this.required = required;
            this.example = example;
        }

        public String getType() { return type; }
        public String getDescription() { return description; }
        public boolean isRequired() { return required; }
        public String getExample() { return example; }
    }
}
