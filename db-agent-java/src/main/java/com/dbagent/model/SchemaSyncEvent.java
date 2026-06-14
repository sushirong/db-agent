package com.dbagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表结构同步 SSE 事件
 */
@Data
@AllArgsConstructor
public class SchemaSyncEvent {

    public enum Type {start, progress, complete, error}

    private Type type;
    private int current;
    private int total;
    private String tableName;
    private String status;
    private String message;
    private Integer success;
    private Integer fail;

    public static SchemaSyncEvent start(int total) {
        return new SchemaSyncEvent(Type.start, 0, total, null, null, null, null, null);
    }

    public static SchemaSyncEvent progress(int current, int total, String tableName, String status, String message) {
        return new SchemaSyncEvent(Type.progress, current, total, tableName, status, message, null, null);
    }

    public static SchemaSyncEvent complete(int total, int success, int fail, String message) {
        return new SchemaSyncEvent(Type.complete, 0, total, null, null, message, success, fail);
    }

    public static SchemaSyncEvent error(String message) {
        return new SchemaSyncEvent(Type.error, 0, 0, null, "error", message, null, null);
    }
}
