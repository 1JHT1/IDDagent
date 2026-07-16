package com.IDDagent.skill;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PotentialCustomerSkill {

    private static final String SUMMARY_FILE = "data/potential_customers.json";
    private static final String DETAILS_FILE = "data/potential_customer_details.json";
    private static final String UPLOADED_FILE = "data/uploaded_customers.json";
    private static final String UPLOAD_SOURCE_ID = "user_upload";
    private static final String UPLOAD_SOURCE_NAME = "用户自定义上传";

    private final SkillRegistry registry;

    public PotentialCustomerSkill(SkillRegistry registry) {
        this.registry = registry;
    }

    //TODO 对公修改
    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "recommend_corporate_customers",
                "当用户询问推荐拓户（开户）客户清单、潜客名单、推荐开户客户、查看客户详情、" +
                        "上传客户清单、导入客户、上传Excel客户时调用此技能。" +
                        "获取基于用户画像的潜在对公开户客户列表，并展示上传入口。",
                this::handle,
                Map.of(
                        "source_id", new Skill.SkillParam("string",
                                "客户清单来源ID，如要查看具体来源的客户明细则传入。可选值: corp_deposit_agent(对公存款智能体), user_upload(用户自定义上传)",
                                false, "corp_deposit_agent")
                )
        ));
    }

    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String sourceId = (String) params.getOrDefault("source_id", null);

        if (UPLOAD_SOURCE_ID.equals(sourceId)) {
            List<Map<String, Object>> uploaded = loadUploaded(userId);
            uploaded.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("score", 0)).doubleValue(),
                    ((Number) a.getOrDefault("score", 0)).doubleValue()));
            Map<String, Object> result = new HashMap<>();
            result.put("action", "detail");
            result.put("source_id", sourceId);
            result.put("customers", uploaded);
            return result;
        }

        if (sourceId != null) {
            Map<String, Object> details = DataLoader.loadJson(DETAILS_FILE);
            Map<String, Object> userData = DataLoader.getMap(details, userId);
            Map<String, Object> sources = DataLoader.getMap(userData, "sources");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> customerList = (List<Map<String, Object>>) sources.getOrDefault(sourceId, List.of());
            customerList.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("score", 0)).doubleValue(),
                    ((Number) a.getOrDefault("score", 0)).doubleValue()));
            Map<String, Object> result = new HashMap<>();
            result.put("action", "detail");
            result.put("source_id", sourceId);
            result.put("customers", customerList);
            return result;
        }

        Map<String, Object> summary = DataLoader.loadJson(SUMMARY_FILE);
        Map<String, Object> userData = DataLoader.getMap(summary, userId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) userData.getOrDefault("sources", new ArrayList<>());

        List<Map<String, Object>> uploaded = loadUploaded(userId);
        if (!uploaded.isEmpty()) {
            Map<String, Object> uploadSource = new HashMap<>();
            uploadSource.put("source_id", UPLOAD_SOURCE_ID);
            uploadSource.put("source_name", UPLOAD_SOURCE_NAME);
            uploadSource.put("customer_count", uploaded.size());
            // return mutable copy
            List<Map<String, Object>> mutableSources = new ArrayList<>(sources);
            mutableSources.add(uploadSource);
            sources = mutableSources;
        }

        if (sources.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("action", "summary");
            result.put("sources", List.of());
            result.put("message", "暂无拓户客户清单数据");
            return result;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("action", "summary");
        result.put("sources", sources);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadUploaded(String userId) {
        Map<String, Object> data = DataLoader.loadJson(UPLOADED_FILE);
        Object userData = data.get(userId);
        if (userData instanceof List) return (List<Map<String, Object>>) userData;
        return List.of();
    }
}
