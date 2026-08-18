package com.IDDagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {
    private String id;
    private String role;
    private String content;
    private String createdAt;
    private List<Map<String, Object>> attachments;
    /** 结构化额外数据（任务规划面板/确认卡/进度气泡等前端本地渲染的卡片消息） */
    private Map<String, Object> extra;

    public Message(String id, String role, String content, String createdAt) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }
}
