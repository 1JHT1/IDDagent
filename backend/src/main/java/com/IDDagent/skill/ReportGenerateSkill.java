package com.IDDagent.skill;

import com.IDDagent.service.UserStoreService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ReportGenerateSkill {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerateSkill.class);
    private static final String TEMPLATES_FILE = "data/report_templates.json";

    private final SkillRegistry registry;
    private final UserStoreService userStoreService;

    public ReportGenerateSkill(SkillRegistry registry, UserStoreService userStoreService) {
        this.registry = registry;
        this.userStoreService = userStoreService;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "generate_report",
                "当用户需要生成尽调报告、财务分析报告、授信评估报告、上传附件生成报告、" +
                        "需要报告模板时调用此技能。先展示模板列表让用户选择，选择后引导用户跳转到编辑页面上传附件并生成报告。",
                this::handle,
                Map.of(
                        "template_id", new Skill.SkillParam("string", "模板ID，从模板列表中选择", false, ""),
                        "company_name", new Skill.SkillParam("string", "企业名称", false, ""),
                        "credit_code", new Skill.SkillParam("string", "统一信用代码", false, "")
                )
        ).withMeta("尽调报告生成",
                List.of("生成报告", "尽调报告", "财务分析报告", "授信评估", "报告模板", "生成尽调", "智能尽调", "上传资料生成报告", "生成"),
                List.of("历史", "查询", "查一下", "查看", "查找"),
                "法人", 60, "report"));
    }

    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String templateId = (String) params.getOrDefault("template_id", "");
        // 机构优先取参数（协调器提取），为空时从用户所属机构兑底
        String organization = (String) params.getOrDefault("organization", "");
        if (organization == null || organization.isEmpty()) {
            organization = userStoreService.getUser(userId)
                    .map(u -> u.getOrDefault("bank_institution", ""))
                    .orElse("");
            log.info("报告技能使用用户机构: userId={}, organization={}", userId, organization);
        }

        // 没有模板ID → 返回模板列表让用户选择（按机构过滤）
        if (templateId.isEmpty()) {
            return showTemplates(organization);
        }

        // 有模板ID → 返回跳转信息
        Map<String, Object> template = findTemplate(templateId);
        if (template == null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("action", "not_found");
            resp.put("message", "未找到模板 ID=" + templateId);
            return resp;
        }

        String templateName = (String) template.getOrDefault("name", "");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("action", "result");
        resp.put("_skill_name", "generate_report");
        resp.put("stage", "redirect");
        resp.put("template_id", templateId);
        resp.put("template_name", templateName);
        resp.put("template_icon", template.getOrDefault("icon", "📄"));
        resp.put("template_description", template.get("description"));
        resp.put("accepted_types", template.get("accepted_types"));
        resp.put("required_fields", template.get("required_fields"));
        resp.put("message", "请在报告编辑页面中上传附件并生成报告");
        return resp;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> showTemplates(String organization) {
        Map<String, Object> data = DataLoader.loadJson(TEMPLATES_FILE);
        List<Map<String, Object>> templates = (List<Map<String, Object>>) data.getOrDefault("templates", List.of());

        // 按机构过滤：只返回匹配机构或无机构的模板
        if (organization != null && !organization.isEmpty()) {
            templates = templates.stream()
                    .filter(t -> {
                        String org = (String) t.getOrDefault("organization", "");
                        return org.isEmpty() || organization.equals(org);
                    })
                    .toList();
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("action", "result");
        resp.put("_skill_name", "generate_report");
        resp.put("stage", "templates");
        resp.put("templates", templates);
        resp.put("organization", organization);
        resp.put("message", "请选择需要生成的报告模板");
        return resp;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findTemplate(String templateId) {
        Map<String, Object> data = DataLoader.loadJson(TEMPLATES_FILE);
        List<Map<String, Object>> templates = (List<Map<String, Object>>) data.getOrDefault("templates", List.of());
        for (Map<String, Object> t : templates) {
            if (templateId.equals(t.get("id"))) return t;
        }
        return null;
    }
}
