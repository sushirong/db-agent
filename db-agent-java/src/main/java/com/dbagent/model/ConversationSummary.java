package com.dbagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话摘要数据
 * 用于左侧历史会话列表展示标题、最近消息和更新时间
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummary {

    /** 会话唯一标识 */
    private String id;

    /** 会话标题，默认来自用户发起会话的提问内容 */
    private String title;

    /** 最近一条消息的摘要内容 */
    private String lastMessage;

    /** 会话更新时间，使用毫秒时间戳方便前端排序和格式化 */
    private Long timestamp;
}
