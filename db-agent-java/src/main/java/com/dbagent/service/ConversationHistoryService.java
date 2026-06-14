package com.dbagent.service;

import java.util.List;

import com.dbagent.model.ConversationMessage;
import com.dbagent.model.ConversationSummary;
import com.dbagent.model.SaveMessageRequest;

/**
 * 会话历史服务
 */
public interface ConversationHistoryService {

    /**
     * 查询全部会话摘要
     */
    List<ConversationSummary> listConversations();

    /**
     * 查询指定会话的完整消息
     */
    List<ConversationMessage> listMessages(String conversationId);

    /**
     * 保存单条会话消息
     */
    ConversationSummary saveMessage(String conversationId, SaveMessageRequest request);
}
