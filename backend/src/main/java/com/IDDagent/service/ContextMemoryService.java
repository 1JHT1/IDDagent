package com.IDDagent.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContextMemoryService {

    private final Map<String, ConversationContext> store = new ConcurrentHashMap<>();

    public ConversationContext get(String conversationId) {
        return store.getOrDefault(conversationId, new ConversationContext());
    }

    public void update(String conversationId, String companyName, String creditCode) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        if (companyName != null && !companyName.isEmpty()) ctx.companyName = companyName;
        if (creditCode != null && !creditCode.isEmpty()) ctx.creditCode = creditCode;
    }

    public void updateAttachment(String conversationId, String attachmentUrl) {
        if (attachmentUrl == null || attachmentUrl.isEmpty()) return;
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.attachmentUrl = attachmentUrl;
    }

    public void clearAttachment(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) ctx.attachmentUrl = "";
    }

    public void clear(String conversationId) {
        store.remove(conversationId);
    }

    public static class ConversationContext {
        public String companyName = "";
        public String creditCode = "";
        public String attachmentUrl = "";

        public boolean isEmpty() {
            return (companyName == null || companyName.isEmpty())
                    && (creditCode == null || creditCode.isEmpty());
        }
    }
}
