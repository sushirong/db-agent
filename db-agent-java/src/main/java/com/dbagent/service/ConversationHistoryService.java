package com.dbagent.service;

import java.sql.ResultSet;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.dbagent.model.ConversationMessage;
import com.dbagent.model.ConversationSummary;
import com.dbagent.model.SaveMessageRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话历史服务
 * 使用业务库永久保存前端聊天会话和消息内容
 */
@Slf4j
@Service
public class ConversationHistoryService {

    private static final int TITLE_MAX_LENGTH = 24;
    private static final int LAST_MESSAGE_MAX_LENGTH = 80;

    private final JdbcTemplate primaryJdbcTemplate;

    public ConversationHistoryService(@Qualifier("primaryJdbcTemplate") JdbcTemplate primaryJdbcTemplate) {
        this.primaryJdbcTemplate = primaryJdbcTemplate;
        log.info("初始化会话历史服务，准备检查历史表结构");
        ensureHistoryTables();
    }

    /**
     * 查询全部会话摘要
     * 会话按照更新时间倒序返回
     */
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
        log.info(
                "历史会话摘要查询完成 conversationCount={}, costMs={}",
                conversations.size(),
                System.currentTimeMillis() - startTime
        );
        return conversations;
    }

    /**
     * 查询指定会话的完整消息
     * 消息按照创建时间升序返回
     */
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
        log.info(
                "会话消息查询完成 conversationId={}, messageCount={}, costMs={}",
                conversationId,
                messages.size(),
                System.currentTimeMillis() - startTime
        );
        return messages;
    }

    /**
     * 保存单条会话消息
     * 消息内容会持久化到数据库，并刷新会话最近消息和更新时间
     */
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
                conversationId,
                messageId,
                safeRole,
                safeContent.length(),
                messageTime
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
        primaryJdbcTemplate.update(
                insertMessageSql,
                messageId,
                conversationId,
                safeRole,
                safeContent,
                messageTime
        );

        refreshConversationSummary(conversationId, safeContent, messageTime);
        ConversationSummary summary = getConversation(conversationId);
        log.info(
                "会话消息保存完成 conversationId={}, messageId={}, role={}, costMs={}",
                conversationId,
                messageId,
                safeRole,
                System.currentTimeMillis() - startTime
        );
        return summary;
    }

    /**
     * 获取单个会话摘要
     */
    private ConversationSummary getConversation(String conversationId) {
        long startTime = System.currentTimeMillis();
        String sql = """
                SELECT id, title, last_message, updated_at
                FROM agent_conversations
                WHERE id = ?
                """;

        ConversationSummary summary = primaryJdbcTemplate.queryForObject(sql, (ResultSet rs, int rowNum) -> ConversationSummary.builder()
                .id(rs.getString("id"))
                .title(rs.getString("title"))
                .lastMessage(rs.getString("last_message"))
                .timestamp(rs.getTimestamp("updated_at").getTime())
                .build(), conversationId);
        log.debug(
                "单个会话摘要查询完成 conversationId={}, costMs={}",
                conversationId,
                System.currentTimeMillis() - startTime
        );
        return summary;
    }

    /**
     * 会话不存在时创建会话记录
     * 标题来自创建会话的消息摘要
     */
    private void ensureConversationExists(String conversationId, String content, long messageTime) {
        long startTime = System.currentTimeMillis();
        String title = createPreviewText(content, TITLE_MAX_LENGTH, "新对话");
        String lastMessage = createPreviewText(content, LAST_MESSAGE_MAX_LENGTH, "新消息");

        String sql = """
                INSERT INTO agent_conversations (id, title, last_message, created_at, updated_at)
                VALUES (?, ?, ?, FROM_UNIXTIME(? / 1000), FROM_UNIXTIME(? / 1000))
                ON DUPLICATE KEY UPDATE id = id
                """;
        primaryJdbcTemplate.update(sql, conversationId, title, lastMessage, messageTime, messageTime);
        log.debug(
                "会话存在性检查完成 conversationId={}, title={}, costMs={}",
                conversationId,
                title,
                System.currentTimeMillis() - startTime
        );
    }

    /**
     * 刷新会话摘要信息
     * 最近消息跟随当前保存的消息内容变化
     */
    private void refreshConversationSummary(String conversationId, String content, long messageTime) {
        long startTime = System.currentTimeMillis();
        String lastMessage = createPreviewText(content, LAST_MESSAGE_MAX_LENGTH, "新消息");
        String sql = """
                UPDATE agent_conversations
                SET last_message = ?, updated_at = FROM_UNIXTIME(? / 1000)
                WHERE id = ?
                """;
        primaryJdbcTemplate.update(sql, lastMessage, messageTime, conversationId);
        log.debug(
                "会话摘要刷新完成 conversationId={}, lastMessage={}, costMs={}",
                conversationId,
                lastMessage,
                System.currentTimeMillis() - startTime
        );
    }

    /**
     * 创建适合列表展示的文本摘要
     * 空白字符会被压缩成单个空格，超长文本会截断
     */
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

    /**
     * 消息角色限制为前端可识别的角色值
     */
    private String normalizeRole(String role) {
        if ("user".equals(role) || "assistant".equals(role)) {
            return role;
        }
        return "assistant";
    }

    /**
     * 创建历史会话所需数据表
     * 表不存在时自动初始化，方便本地开发和首次部署
     */
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
        log.info(
                "会话消息表检查完成 tableName=agent_messages, costMs={}",
                System.currentTimeMillis() - startTime
        );
    }
}
