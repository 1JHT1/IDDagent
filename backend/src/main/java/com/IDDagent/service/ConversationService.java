package com.IDDagent.service;
/*
*
* 这个java文件下用来处理对话逻辑
* */
import com.IDDagent.model.Conversation;
import com.IDDagent.model.ConversationListItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    /** 会话持久化文件（与 users.json 同目录，后端重启后对话记录仍保留） */
    private static final String CONVERSATIONS_FILE = "data/conversations.json";
    private final ObjectMapper mapper = new ObjectMapper();

    // { userId: { convId: Conversation } }
    private final Map<String, Map<String, Conversation>> conversations = new ConcurrentHashMap<>();

    // { conversationId: { creditCode: [skillName, ...] } }
    private final Map<String, Map<String, List<String>>> conversationSkills = new ConcurrentHashMap<>();

    // { conversationId: [notification, ...] }
    private final Map<String, List<Map<String, Object>>> accountNotifications = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        load();
    }

    /** 启动时从 data/conversations.json 加载会话到内存 */
    private void load() {
        try {
            Path path = Paths.get(CONVERSATIONS_FILE);
            if (Files.exists(path)) {
                Map<String, Map<String, Conversation>> loaded = mapper.readValue(
                        path.toFile(),
                        new TypeReference<Map<String, Map<String, Conversation>>>() {});
                conversations.putAll(loaded);
                log.info("Loaded {} users' conversations from {}", conversations.size(), path.toAbsolutePath());
            }
        } catch (IOException e) {
            log.warn("Failed to load conversations file: {}", e.getMessage());
        }
    }

    /** 将内存中的全部会话写入 data/conversations.json（消息增删后调用） */
    public synchronized void persist() {
        try {
            Path path = Paths.get(CONVERSATIONS_FILE);
            Files.createDirectories(path.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), conversations);
        } catch (IOException e) {
            log.error("Failed to save conversations file: {}", e.getMessage());
        }
    }

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
        persist();
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
        boolean removed = getUserConvs(userId).remove(convId) != null;
        if (removed) {
            persist();
        }
        return removed;
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
