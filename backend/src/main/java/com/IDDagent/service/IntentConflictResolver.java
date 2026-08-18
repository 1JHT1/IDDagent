package com.IDDagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 意图冲突检测与消解（Phase 4）。
 * 冲突分级处理：
 * 1. 同技能不同主体：同一技能意图涉及 ≥2 个不同企业 → 需要用户澄清（clarification 选项气泡）
 * 2. 主体切换矛盾：intent 的 company_name 与会话上下文不同 → 自动消解（以最新消息为准，参数优先，不发澄清）
 * 3. 技能级互斥规则：MUTEX_RULES 预置空结构，留扩展位（当前项目无互斥技能）
 * 无冲突返回 null，调用方走正常规划/单意图路径。
 */
@Component
public class IntentConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(IntentConflictResolver.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 技能级互斥规则表：key=技能名，value=与其互斥的技能集合。
     * 当前项目没有"删除报告"等互斥技能，预置空结构留扩展位；
     * 未来登记互斥关系后，detectAndResolve 会自动检查并消解（保留优先级别较高的意图）。
     */
    private static final Map<String, Set<String>> MUTEX_RULES = Map.of();

    /**
     * 检测并消解多意图中的冲突。
     *
     * @param intents 多意图决策的 intents 列表（可能被就地修改：互斥消解时移除低优先级意图）
     * @param ctx     会话上下文记忆（可能为临时对象，仅用于读取 companyName）
     * @return 无冲突返回 null；需要用户澄清时返回
     *         {"action":"clarification","question":"...","options":[{"label":"...","value":"{\"company_name\":\"...\"}"}],
     *          "context":{"skill":"...","params":{...}}}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> detectAndResolve(List<Map<String, Object>> intents,
                                                ContextMemoryService.ConversationContext ctx) {
        if (intents == null || intents.isEmpty()) return null;
        if (ctx == null) ctx = new ContextMemoryService.ConversationContext();

        // 第 1 级：同技能不同主体 → clarification（选项 value 为 JSON 字符串，前端点击原样发送）
        Map<String, Set<String>> skillCompanies = new LinkedHashMap<>();
        Map<String, Map<String, Object>> skillToFirstIntent = new LinkedHashMap<>();
        for (Map<String, Object> intent : intents) {
            String skill = String.valueOf(intent.getOrDefault("skill", ""));
            Map<String, Object> params = (Map<String, Object>) intent.getOrDefault("params", Map.of());
            Object company = params.get("company_name");
            if (company == null || String.valueOf(company).isBlank()) continue;
            skillCompanies.computeIfAbsent(skill, k -> new LinkedHashSet<>()).add(String.valueOf(company));
            skillToFirstIntent.putIfAbsent(skill, intent);
        }
        for (Map.Entry<String, Set<String>> entry : skillCompanies.entrySet()) {
            if (entry.getValue().size() >= 2) {
                String skill = entry.getKey();
                log.info("Conflict detected: skill '{}' targets multiple companies {}", skill, entry.getValue());
                return buildClarification(skill, skillToFirstIntent.get(skill), entry.getValue(), intents);
            }
        }

        // 第 2 级：主体切换矛盾 → 自动消解（以最新消息为准，直接采用 intent 自己的公司名，不发澄清）
        if (ctx.companyName != null && !ctx.companyName.isEmpty()) {
            for (Map<String, Object> intent : intents) {
                Map<String, Object> params = (Map<String, Object>) intent.getOrDefault("params", Map.of());
                Object company = params.get("company_name");
                if (company != null && !ctx.companyName.equals(String.valueOf(company))) {
                    log.info("Auto-resolved company switch: context '{}' -> intent '{}' (latest message wins)",
                            ctx.companyName, company);
                }
            }
        }

        // 第 3 级：技能级互斥规则（预置空结构，此处仅为扩展位预留检查逻辑）
        if (!MUTEX_RULES.isEmpty()) {
            Set<String> skills = new LinkedHashSet<>();
            for (Map<String, Object> intent : intents) {
                skills.add(String.valueOf(intent.getOrDefault("skill", "")));
            }
            for (Map.Entry<String, Set<String>> rule : MUTEX_RULES.entrySet()) {
                if (skills.contains(rule.getKey()) && skills.stream().anyMatch(rule.getValue()::contains)) {
                    log.warn("Mutually exclusive skills detected: {} conflicts with {}, keeping the first one",
                            rule.getKey(), rule.getValue());
                    intents.removeIf(i -> rule.getValue().contains(String.valueOf(i.get("skill")))
                            && !rule.getKey().equals(String.valueOf(i.get("skill"))));
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * 构造澄清决策：question + options（value 为 JSON 字符串）+ context（技能与参数，供用户选择后直接执行）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildClarification(String skill, Map<String, Object> intent,
                                                   Set<String> companies,
                                                   List<Map<String, Object>> intents) {
        Map<String, Object> params = new LinkedHashMap<>(
                (Map<String, Object>) intent.getOrDefault("params", Map.of()));
        List<Map<String, Object>> options = new ArrayList<>();
        for (String company : companies) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("label", company);
            try {
                // value 为 JSON 字符串，前端点击后原样发送，后端解析合并到技能参数
                option.put("value", mapper.writeValueAsString(Map.of("company_name", company)));
            } catch (Exception e) {
                log.warn("Failed to serialize clarification option for '{}': {}", company, e.getMessage());
                option.put("value", "{\"company_name\":\"" + company + "\"}");
            }
            options.add(option);
        }
        // "全部执行"选项：value 为 execute_all 标记，后端识别后放行完整 intents 到任务规划
        Map<String, Object> allOption = new LinkedHashMap<>();
        allOption.put("label", "全部执行");
        allOption.put("value", "{\"action\":\"execute_all\"}");
        options.add(allOption);

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "clarification");
        decision.put("question", "检测到同一查询涉及多个企业，请确认要对哪家企业执行「" + skill + "」？");
        decision.put("options", options);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("skill", skill);
        context.put("params", params);
        // 完整 intents 供"全部执行"放行（用户确认后跳过冲突检测直接构建规划）
        context.put("intents", intents);
        decision.put("context", context);
        return decision;
    }
}
