package com.dbagent.service.impl;

import java.sql.ResultSet;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.dbagent.model.ConversationMessage;
import com.dbagent.model.ConversationSummary;
import com.dbagent.model.SaveMessageRequest;
import com.dbagent.service.ConversationHistoryService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ConversationHistoryServiceImpl implements ConversationHistoryService {

    private static final int TITLE_MAX_LENGTH = 24;
    private static final int LAST_MESSAGE_MAX_LENGTH = 80;

    private final JdbcTemplate primaryJdbcTemplate;

    public ConversationHistoryServiceImpl(@Qualifier("primaryJdbcTemplate") JdbcTemplate primaryJdbcTemplate) {
        this.primaryJdbcTemplate = primaryJdbcTemplate;
        log.info("初始化会话历史服务，准备检查历史表结构");
        ensureHistoryTables();
    }

    @Override
    public List<ConversationSummary> listConversations() {
        long startTime = System.currentTimeMillis();
        String sql = """
                SELECT id, title, last_message, updated_at
                FROM agent_conversations
                ORDER BY updated_at DESC
                """;

        List<ConversationSummary> conversations = primaryJdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> ConversationSummary.builder()
                .id(rs.getString("id"))
                .title(rs.getString("title"))
                .lastMessage(rs.getString("last_message"))
                .timestamp(rs.getTimestamp("updated_at").getTime())
                .build());
        log.info("历史会话摘要查询完成 conversationCount={}, costMs={}", conversations.size(), System.currentTimeMillis() - startTime);
        return conversations;
    }

    @Override
    public List<ConversationMessage> listMessages(String conversationId) {
        long startTime = System.currentTimeMillis();
        String sql = """
                SELECT id, role, content, created_at
                FROM agent_messages
                WHERE conversation_id = ?
                ORDER BY created_at ASC, id ASC
                """;

        List<ConversationMessage> messages = primaryJdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> ConversationMessage.builder()
                .id(rs.getString("id"))
                .role(rs.getString("role"))
                .content(rs.getString("content"))
                .timestamp(rs.getTimestamp("created_at").getTime())
                .build(), conversationId);
        log.info("会话消息查询完成 conversationId={}, messageCount={}, costMs={}", conversationId, messages.size(), System.currentTimeMillis() - startTime);
        return messages;
    }

    @Override
    public ConversationSummary saveMessage(String conversationId, SaveMessageRequest request) {
        long startTime = System.currentTimeMillis();
        long messageTime = request.getTimestamp() != null ? request.getTimestamp() : System.currentTimeMillis();
        String safeContent = request.getContent() != null ? request.getContent() : "";
        String safeRole = normalizeRole(request.getRole());
        String messageId = request.getId() != null && !request.getId().isBlank()
                ? request.getId()
                : conversationId + "-" + messageTime;

        log.info(
                "开始保存会话消息 conversationId={}, messageId={}, role={}, contentLength={}, messageTime={}",
                conversationId, messageId, safeRole, safeContent.length(), messageTime
        );

        ensureConversationExists(conversationId, safeContent, messageTime);

        String insertMessageSql = """
                INSERT INTO agent_messages (id, conversation_id, role, content, created_at)
                VALUES (?, ?, ?, ?, FROM_UNIXTIME(? / 1000))
                ON DUPLICATE KEY UPDATE
                    role = VALUES(role),
                    content = VALUES(content),
                    created_at = VALUES(created_at)
                """;
        primaryJdbcTemplate.update(insertMessageSql, messageId, conversationId, safeRole, safeContent, messageTime);

        refreshConversationSummary(conversationId, safeContent, messageTime);
        ConversationSummary summary = getConversation(conversationId);
        log.info("会话消息保存完成 conversationId={}, messageId={}, role={}, costMs={}", conversationId, messageId, safeRole, System.currentTimeMillis() - startTime);
        return summary;
    }

    private ConversationSummary getConversation(String conversationId) {
        String sql = """
                SELECT id, title, last_message, updated_at
                FROM agent_conversations
                WHERE id = ?
                """;
        return primaryJdbcTemplate.queryForObject(sql, (ResultSet rs, int rowNum) -> ConversationSummary.builder()
                .id(rs.getString("id"))
                .title(rs.getString("title"))
                .lastMessage(rs.getString("last_message"))
                .timestamp(rs.getTimestamp("updated_at").getTime())
                .build(), conversationId);
    }

    private void ensureConversationExists(String conversationId, String content, long messageTime) {
        String title = createPreviewText(content, TITLE_MAX_LENGTH, "新对话");
        String lastMessage = createPreviewText(content, LAST_MESSAGE_MAX_LENGTH, "新消息");

        String sql = """
                INSERT INTO agent_conversations (id, title, last_message, created_at, updated_at)
                VALUES (?, ?, ?, FROM_UNIXTIME(? / 1000), FROM_UNIXTIME(? / 1000))
                ON DUPLICATE KEY UPDATE id = id
                """;
        primaryJdbcTemplate.update(sql, conversationId, title, lastMessage, messageTime, messageTime);
    }

    private void refreshConversationSummary(String conversationId, String content, long messageTime) {
        String lastMessage = createPreviewText(content, LAST_MESSAGE_MAX_LENGTH, "新消息");
        String sql = "UPDATE agent_conversations SET last_message = ?, updated_at = FROM_UNIXTIME(? / 1000) WHERE id = ?";
        primaryJdbcTemplate.update(sql, lastMessage, messageTime, conversationId);
    }

    private String createPreviewText(String content, int maxLength, String fallbackValue) {
        String normalizedContent = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalizedContent.isEmpty()) {
            return fallbackValue;
        }
        if (normalizedContent.length() <= maxLength) {
            return normalizedContent;
        }
        return normalizedContent.substring(0, maxLength) + "...";
    }

    private String normalizeRole(String role) {
        if ("user".equals(role) || "assistant".equals(role)) {
            return role;
        }
        return "assistant";
    }

    private void ensureHistoryTables() {
        long startTime = System.currentTimeMillis();
        String conversationTableSql = """
                CREATE TABLE IF NOT EXISTS agent_conversations (
                    id VARCHAR(64) PRIMARY KEY COMMENT '会话ID',
                    title VARCHAR(255) NOT NULL COMMENT '会话标题',
                    last_message VARCHAR(500) NOT NULL COMMENT '最近消息摘要',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    INDEX idx_updated_at (updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent会话历史表'
                """;

        String messageTableSql = """
                CREATE TABLE IF NOT EXISTS agent_messages (
                    id VARCHAR(64) PRIMARY KEY COMMENT '消息ID',
                    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
                    role VARCHAR(20) NOT NULL COMMENT '消息角色',
                    content LONGTEXT NOT NULL COMMENT '消息内容',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    INDEX idx_conversation_created_at (conversation_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent会话消息表'
                """;

        primaryJdbcTemplate.execute(conversationTableSql);
        log.info("会话历史表检查完成 tableName=agent_conversations");
        primaryJdbcTemplate.execute(messageTableSql);
        log.info("会话消息表检查完成 tableName=agent_messages, costMs={}", System.currentTimeMillis() - startTime);
    }
}
