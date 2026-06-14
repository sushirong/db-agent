package com.dbagent.service;

import java.util.List;

import com.dbagent.model.TableSchema;

/**
 * 数据库表结构提取服务
 */
public interface DatabaseSchemaService {

    /**
     * 获取指定数据库的所有表结构
     */
    List<TableSchema> getAllTableSchemas(String databaseName);

    /**
     * 获取单张表的完整结构信息
     */
    TableSchema getTableSchema(String databaseName, String tableName);

    /**
     * 获取单张表的格式化文本描述
     */
    String getFormattedTableSchema(String databaseName, String tableName);
}
