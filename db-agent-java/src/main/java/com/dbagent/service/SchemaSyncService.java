package com.dbagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表结构同步服务
 */
public interface SchemaSyncService {

    /**
     * 同步进度回调
     */
    @FunctionalInterface
    interface ProgressCallback {
        void onProgress(int current, int total, String tableName, String status, String message);
    }

    /**
     * 同步结果
     */
    @Data
    @AllArgsConstructor
    class SyncResult {
        private int total;
        private int success;
        private int fail;
        private String message;
    }

    /**
     * 全量同步：将指定数据库的所有表结构同步到 Python AI 服务
     *
     * @param databaseName 数据库名称
     * @param callback     进度回调（可为 null）
     * @return 同步结果
     */
    SyncResult syncAllSchemas(String databaseName, ProgressCallback callback);

    /**
     * 增量同步：只同步新增、变更和删除的表
     *
     * @param databaseName 数据库名称
     * @param callback     进度回调（可为 null）
     * @return 同步结果
     */
    SyncResult incrementalSync(String databaseName, ProgressCallback callback);
}
