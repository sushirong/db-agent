package com.dbagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话消息数据
 * 用于前端打开历史会话时恢复完整聊天内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {

    /** 消息唯一标识 */
    private String id;

    /** 消息角色，取值为 user 或 assistant */
    private String role;

    /** 消息正文内容 */
    private String content;

    /** 消息创建时间，使用毫秒时间戳方便前端展示 */
    private Long timestamp;
}
