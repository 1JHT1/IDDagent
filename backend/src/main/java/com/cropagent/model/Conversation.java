package com.cropagent.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class Conversation {
    private String id;
    private String userId;
    private String title;
    private List<Message> messages = new ArrayList<>();
    private String createdAt;
    private String updatedAt;
}
