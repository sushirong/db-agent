package com.dbagent.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dbagent.model.ConversationMessage;
import com.dbagent.model.ConversationSummary;
import com.dbagent.model.SaveMessageRequest;
import com.dbagent.service.ConversationHistoryService;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话历史 API
 * 提供前端历史会话列表、消息详情和消息持久化能力
 */
@Slf4j
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationHistoryService conversationHistoryService;

    public ConversationController(ConversationHistoryService conversationHistoryService) {
        this.conversationHistoryService = conversationHistoryService;
    }

    /**
     * 获取历史会话列表
     */
    @GetMapping
    public ResponseEntity<List<ConversationSummary>> listConversations() {
        long startTime = System.currentTimeMillis();
        log.info("收到历史会话列表查询请求");
        List<ConversationSummary> conversations = conversationHistoryService.listConversations();
        log.info(
                "历史会话列表查询完成 conversationCount={}, costMs={}",
                conversations.size(),
                System.currentTimeMillis() - startTime
        );
        return ResponseEntity.ok(conversations);
    }

    /**
     * 获取指定会话的消息列表
     */
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<ConversationMessage>> listMessages(@PathVariable String conversationId) {
        long startTime = System.currentTimeMillis();
        log.info("收到会话消息查询请求 conversationId={}", conversationId);
        List<ConversationMessage> messages = conversationHistoryService.listMessages(conversationId);
        log.info(
                "会话消息查询完成 conversationId={}, messageCount={}, costMs={}",
                conversationId,
                messages.size(),
                System.currentTimeMillis() - startTime
        );
        return ResponseEntity.ok(messages);
    }

    /**
     * 保存指定会话中的一条消息
     */
    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<ConversationSummary> saveMessage(
            @PathVariable String conversationId,
            @RequestBody SaveMessageRequest request
    ) {
        long startTime = System.currentTimeMillis();
        log.info(
                "收到会话消息保存请求 conversationId={}, requestPresent={}, messageId={}, role={}, contentLength={}",
                conversationId,
                request != null,
                request == null ? null : request.getId(),
                request == null ? null : request.getRole(),
                request == null || request.getContent() == null ? 0 : request.getContent().length()
        );
        if (conversationId == null || conversationId.isBlank() || request == null) {
            log.warn("会话消息保存请求非法 conversationId={}, requestPresent={}", conversationId, request != null);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        ConversationSummary summary = conversationHistoryService.saveMessage(conversationId, request);
        log.info(
                "会话消息保存完成 conversationId={}, messageId={}, role={}, costMs={}",
                conversationId,
                request.getId(),
                request.getRole(),
                System.currentTimeMillis() - startTime
        );
        return ResponseEntity.ok(summary);
    }
}
