package com.IDDagent.service;
/*
*
* 这个java文件下用来处理对话逻辑
* */
import com.IDDagent.model.Conversation;
import com.IDDagent.model.ConversationListItem;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationService {

    // { userId: { convId: Conversation } }
    private final Map<String, Map<String, Conversation>> conversations = new ConcurrentHashMap<>();

    // { conversationId: { creditCode: [skillName, ...] } }
    private final Map<String, Map<String, List<String>>> conversationSkills = new ConcurrentHashMap<>();

    // { conversationId: [notification, ...] }
    private final Map<String, List<Map<String, Object>>> accountNotifications = new ConcurrentHashMap<>();

    public Map<String, Conversation> getUserConvs(String userId) {
        return conversations.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
    }

    public Conversation createConversation(String userId, String title) {
        String convId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        Conversation conv = new Conversation();
        conv.setId(convId);
        conv.setUserId(userId);
        conv.setTitle(title != null ? title : "新对话");
        conv.setMessages(new ArrayList<>());
        conv.setCreatedAt(now);
        conv.setUpdatedAt(now);
        getUserConvs(userId).put(convId, conv);
        return conv;
    }

    /*
    * ---------获取会话列表
    * */
    public List<ConversationListItem> listConversations(String userId) {
        List<ConversationListItem> result = new ArrayList<>();
        for (Conversation conv : getUserConvs(userId).values()) {
            result.add(new ConversationListItem(
                    conv.getId(), conv.getUserId(), conv.getTitle(),
                    conv.getMessages().size(), conv.getCreatedAt(), conv.getUpdatedAt()));
        }
        //按updatedAt降序排列
        result.sort((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()));
        return result;
    }

    public Conversation getConversation(String userId, String convId) {
        return getUserConvs(userId).get(convId);
    }

    public boolean deleteConversation(String userId, String convId) {
        return getUserConvs(userId).remove(convId) != null;
    }

    // Track skills called per conversation per company
    public Map<String, List<String>> getConversationSkills(String convId) {
        return conversationSkills.computeIfAbsent(convId, k -> new ConcurrentHashMap<>());
    }

    public List<String> getCompanySkills(String convId, String creditCode) {
        return getConversationSkills(convId).computeIfAbsent(creditCode, k -> new ArrayList<>());
    }

    public void recordSkillCall(String convId, String creditCode, String skillName) {
        List<String> skills = getCompanySkills(convId, creditCode);
        if (!skills.contains(skillName)) {
            skills.add(skillName);
        }
    }

    public List<String> getAllSkills(String convId) {
        List<String> all = new ArrayList<>();
        for (var entry : getConversationSkills(convId).entrySet()) {
            all.addAll(entry.getValue());
        }
        return all;
    }

    // Account notifications
    public void addAccountNotification(String convId, Map<String, Object> notification) {
        accountNotifications.computeIfAbsent(convId, k -> new ArrayList<>()).add(notification);
    }

    public List<Map<String, Object>> popAccountNotifications(String convId) {
        List<Map<String, Object>> notifications = accountNotifications.remove(convId);
        return notifications != null ? notifications : List.of();
    }
}
