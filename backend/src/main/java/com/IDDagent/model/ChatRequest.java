package com.IDDagent.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ChatRequest {
    private String message;
    private String conversationId;
    /** 消息附件列表（每项包含 name/url/size/type） */
    private List<Map<String, Object>> attachments;
}
