package com.IDDagent.service;

import com.IDDagent.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 任务规划器：将多意图 skills 数组转换为有序执行计划。
 * 规则：用户陈述顺序优先、报告依赖前置（仅对素材来源技能生效）、参数传递、去重。
 */
@Component
public class TaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanner.class);

    /** 报告类技能（需要前置查询/核实结果作素材，强制排到最后） */
    private static final Set<String> REPORT_SKILLS = Set.of("generate_report");

    /**
     * 报告素材来源例外：历史尽调查询不产生报告素材，与 generate_report 同现时
     * 不触发"报告排最后"重排，保持用户陈述的语义顺序。
     */
    private static final String NON_FEEDING_SKILL = "query_due_diligence_reports";

    private final SkillRegistry skillRegistry;

    public TaskPlanner(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * 输入多意图 skills 数组，输出有序执行计划。
     * @param skills LLM 输出的 skills 数组，每项含 skill/params
     * @return 有序 PlanTask 列表
     */
    @SuppressWarnings("unchecked")
    public List<PlanTask> plan(List<Map<String, Object>> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }

        // 去重：同一技能只保留首次出现（合并 params）
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> item : skills) {
            String skillName = (String) item.getOrDefault("skill", "");
            if (skillName.isEmpty() || skillRegistry.get(skillName) == null) {
                log.warn("TaskPlanner: skipping unknown skill '{}'", skillName);
                continue;
            }
            if (!deduped.containsKey(skillName)) {
                Map<String, Object> params = item.get("params") instanceof Map
                        ? new LinkedHashMap<>((Map<String, Object>) item.get("params"))
                        : new LinkedHashMap<>();
                deduped.put(skillName, params);
            }
        }

        // 分离报告类与非报告类
        List<Map.Entry<String, Map<String, Object>>> nonReport = new ArrayList<>();
        List<Map.Entry<String, Map<String, Object>>> report = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : deduped.entrySet()) {
            if (REPORT_SKILLS.contains(entry.getKey())) {
                report.add(entry);
            } else {
                nonReport.add(entry);
            }
        }

        // 合并：报告依赖前置仅当存在素材来源技能时生效。
        // 素材来源技能 = 非报告类中除 query_due_diligence_reports 外的查询/核实/风险类技能；
        // 若不存在（如"查询历史尽调 + 生成报告"），保持 LLM 输出的语义顺序，不做重排。
        boolean hasReportFeeding = nonReport.stream()
                .anyMatch(e -> !NON_FEEDING_SKILL.equals(e.getKey()));
        List<Map.Entry<String, Map<String, Object>>> ordered = new ArrayList<>();
        if (hasReportFeeding) {
            // 非报告类保持原始顺序，报告类排最后
            ordered.addAll(nonReport);
            ordered.addAll(report);
        } else {
            ordered.addAll(deduped.entrySet());
        }

        // 生成 PlanTask 列表
        List<PlanTask> plan = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<String, Map<String, Object>> entry = ordered.get(i);
            String dependsOn = null;
            if (REPORT_SKILLS.contains(entry.getKey()) && i > 0) {
                // 报告依赖前置任务
                dependsOn = ordered.get(i - 1).getKey();
            }
            plan.add(new PlanTask(
                    entry.getKey(),
                    entry.getValue(),
                    i + 1,
                    dependsOn,
                    List.of("company_name", "credit_code") // 通用共享参数
            ));
        }

        log.info("TaskPlanner plan: {} tasks", plan.size());
        return plan;
    }

    /**
     * 生成规划文本（用户可见）
     */
    public String buildPlanText(List<PlanTask> plan) {
        if (plan == null || plan.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("我将依次为您执行：");
        String[] numbers = {"①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩"};
        for (int i = 0; i < plan.size(); i++) {
            String label = skillRegistry.getSkillLabel(plan.get(i).skill());
            sb.append(numbers[i < numbers.length ? i : 0]).append(" ").append(label);
            if (i < plan.size() - 1) sb.append(" ");
        }
        return sb.toString();
    }

    /**
     * 有序执行计划中的单个任务
     */
    public record PlanTask(
            String skill,
            Map<String, Object> params,
            int order,
            String dependsOn,
            List<String> requiredParams
    ) {}
}
