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
    /** 扩展标记（如候选确认的 confirmed、静默发送的 silent），随用户消息落盘 */
    private Map<String, Object> extra;
}
