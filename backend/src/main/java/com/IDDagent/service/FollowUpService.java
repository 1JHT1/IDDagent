package com.IDDagent.service;

import com.IDDagent.config.AppConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FollowUpService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
    private static final String WORKFLOW_FILE = "data/follow_up_workflows.md";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");

    private final WebClient webClient;
    private final AppConfig config;
    private String workflowText;

    public FollowUpService(WebClient webClient, AppConfig config) {
        this.webClient = webClient;
        this.config = config;
    }

    private String loadWorkflow() {
        if (workflowText == null) {
            try {
                Path path = Paths.get(WORKFLOW_FILE);
                if (Files.exists(path)) {
                    workflowText = Files.readString(path);
                } else {
                    workflowText = "";
                }
            } catch (IOException e) {
                workflowText = "";
            }
        }
        return workflowText;
    }

    public String predictFollowUp(String skillName, String skillAction, String companyName,
                                   String creditCode, List<String> conversationSkills,
                                   List<String> currentCompanySkills) {
        // No follow-up for non-result actions
        if (skillAction == null || skillAction.equals("not_found")
                || skillAction.equals("ambiguous") || skillAction.equals("error")) {
            return null;
        }
        if (skillName == null || skillName.isEmpty()) {
            return null;
        }

        String workflow = loadWorkflow();
        String allSkillsDone = (conversationSkills != null && !conversationSkills.isEmpty())
                ? String.join("、", conversationSkills) : "无";
        String companySkillsDone = (currentCompanySkills != null && !currentCompanySkills.isEmpty())
                ? String.join("、", currentCompanySkills) : "无";

        String userPrompt = """
                ## 当前上下文

                - 刚完成的技能：%s
                - 当前企业：%s
                - 统一信用代码：%s
                - 本次会话所有已调用过的技能（按顺序，可能跨企业）：%s
                - 当前企业「%s」已调用过的技能：%s

                ## 任务

                根据上述上下文和标准业务流程，预测用户下一步最可能的意图，生成一句自然的追问。

                ## 输出要求

                - 只输出一个 JSON 对象，不要输出其他任何内容
                - 如果判断应该追问，输出：{"suggestion": "追问文本"}
                - 如果判断不应追问，输出：{"suggestion": null}
                - 追问文本必须以"是否需要"开头，使用口语化中文
                - **必须参考「严格反循环规则」和「主体切换规则」做出判断**"""
                .formatted(skillName,
                        companyName != null ? companyName : "无",
                        creditCode != null ? creditCode : "无",
                        allSkillsDone,
                        companyName != null ? companyName : "无",
                        companySkillsDone);
        //TODO 对公修改        
        String systemPrompt = """
                你是一个银行对公客户经理的智能助手。你的任务是在完成当前应答后，主动预测用户的下一步需求。

                ## 标准业务流程

                %s

                ## 重要规则（必须严格遵守）

                - **反循环是第一优先级**：严格按照「严格反循环规则」和「追问走向速查表」判断，禁止追问当前已完成的技能
                - **判断流程闭环**：参考「当前企业已调用过的技能」判断该企业是否已走完风险+拓户+产品全流程，是则不追问
                - **主体切换识别**：当当前企业发生变化时，视为新企业从头开始，不要受旧企业技能历史影响
                - 追问必须自然亲切，以"是否需要"开头
                - 如果当前企业有名称，追问中必须包含「企业名」
                - 不要在已经完成流程闭环后继续追问""".formatted(workflow);

        String apiKey = config.getDeepseek().getApiKey();
        String baseUrl = config.getDeepseek().getBaseUrl();
        String model = config.getModel().getFollowUp();

        if (apiKey == null || apiKey.isEmpty()) return null;

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 150);

            String response = webClient.post()
                    .uri(baseUrl + "/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Map<String, Object> respMap = mapper.readValue(response, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            String text = "";
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                text = (String) message.getOrDefault("content", "");
            }

            Matcher jsonMatch = JSON_PATTERN.matcher(text);
            if (jsonMatch.find()) {
                Map<String, Object> result = mapper.readValue(jsonMatch.group(), new TypeReference<>() {});
                Object suggestion = result.get("suggestion");
                if (suggestion instanceof String s && !s.isBlank()) {
                    log.info("FollowUp suggestion: {}", s);
                    return s.trim();
                }
            }

            log.info("FollowUp: no suggestion (raw: {})", text.length() > 100 ? text.substring(0, 100) : text);
            return null;

        } catch (Exception e) {
            log.error("FollowUp prediction failed: {}", e.getMessage());
            return null;
        }
    }
}
