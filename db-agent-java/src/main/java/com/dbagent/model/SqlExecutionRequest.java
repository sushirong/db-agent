package com.dbagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SQL 执行请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SqlExecutionRequest {

    /** 要执行的 SQL 语句 */
    private String sql;

    /** 数据库名称 (可选，默认使用配置的数据库) */
    private String databaseName;
}
