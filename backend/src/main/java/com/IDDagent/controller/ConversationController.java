package com.IDDagent.controller;

import com.IDDagent.model.*;
import com.IDDagent.service.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /*
    * ------根据创建最新更新时间列举会话列表
    * */
    @GetMapping("/conversations")
    public Mono<Map<String, Object>> listConversations(
            @RequestAttribute("currentUser") UserInfo currentUser) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("conversations", conversationService.listConversations(currentUser.getId()));
            return result;
        });
    }


    /*
    * ---
    * */
    @PostMapping("/conversations")
    public Mono<Map<String, Object>> newConversation(
            @RequestBody ConversationCreate body,
            @RequestAttribute("currentUser") UserInfo currentUser) {
        return Mono.fromCallable(() -> {
            Conversation conv = conversationService.createConversation(currentUser.getId(), body.getTitle());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", conv.getId());
            result.put("user_id", conv.getUserId());
            result.put("title", conv.getTitle());
            result.put("created_at", conv.getCreatedAt());
            return result;
        });
    }

    /*
    * -----获取对应对话id的对话内容
    * */
    @GetMapping("/conversations/{conversationId}")
    public Mono<Conversation> getConversation(
            @PathVariable String conversationId,
            @RequestAttribute("currentUser") UserInfo currentUser) {
        return Mono.fromCallable(() -> {
            Conversation conv = conversationService.getConversation(currentUser.getId(), conversationId);
            if (conv == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
            }
            return conv;
        });
    }

    /*
    * -----删除对话
    * */
    @DeleteMapping("/conversations/{conversationId}")
    public Mono<Map<String, Object>> deleteConversation(
            @PathVariable String conversationId,
            @RequestAttribute("currentUser") UserInfo currentUser) {
        return Mono.fromCallable(() -> {
            if (!conversationService.deleteConversation(currentUser.getId(), conversationId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "deleted");
            result.put("id", conversationId);
            return result;
        });
    }
}
