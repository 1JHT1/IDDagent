package com.cropagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConversationListItem {
    private String id;
    private String userId;
    private String title;
    private int messageCount;
    private String createdAt;
    private String updatedAt;
}
