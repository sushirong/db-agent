package com.dbagent.model;

import lombok.Data;

/**
 * 保存会话消息请求
 * 前端发送用户消息和助手消息时使用该结构写入数据库
 */
@Data
public class SaveMessageRequest {

    /** 消息唯一标识 */
    private String id;

    /** 消息角色，取值为 user 或 assistant */
    private String role;

    /** 消息正文内容 */
    private String content;

    /** 消息创建时间，使用毫秒时间戳 */
    private Long timestamp;
}
