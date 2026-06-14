package com.dbagent.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.dbagent.model.ColumnSchema;
import com.dbagent.model.TableSchema;
import com.dbagent.service.DatabaseSchemaService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DatabaseSchemaServiceImpl implements DatabaseSchemaService {

    private final JdbcTemplate primaryJdbcTemplate;

    public DatabaseSchemaServiceImpl(@Qualifier("primaryJdbcTemplate") JdbcTemplate primaryJdbcTemplate) {
        this.primaryJdbcTemplate = primaryJdbcTemplate;
    }

    @Override
    public List<TableSchema> getAllTableSchemas(String databaseName) {
        long startTime = System.currentTimeMillis();
        log.info("开始提取数据库表结构 databaseName={}", databaseName);
        List<String> tableNames = getAllTableNames(databaseName);
        log.info("数据库表名加载完成 databaseName={}, tableNames={}", databaseName, tableNames);
        List<TableSchema> schemas = new ArrayList<>();

        for (String tableName : tableNames) {
            long tableStartTime = System.currentTimeMillis();
            TableSchema schema = getTableSchema(databaseName, tableName);
            if (schema != null) {
                schemas.add(schema);
                log.info(
                        "单表结构提取完成 databaseName={}, tableName={}, columnCount={}, costMs={}",
                        databaseName, tableName,
                        schema.getColumns() == null ? 0 : schema.getColumns().size(),
                        System.currentTimeMillis() - tableStartTime
                );
            } else {
                log.warn("单表结构提取结果为空 databaseName={}, tableName={}", databaseName, tableName);
            }
        }

        log.info(
                "数据库表结构提取完成 databaseName={}, tableCount={}, costMs={}",
                databaseName, schemas.size(), System.currentTimeMillis() - startTime
        );
        return schemas;
    }

    @Override
    public TableSchema getTableSchema(String databaseName, String tableName) {
        long startTime = System.currentTimeMillis();
        log.info("开始查询单表结构 databaseName={}, tableName={}", databaseName, tableName);
        String tableCommentSql = "SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        String tableComment = primaryJdbcTemplate.queryForObject(tableCommentSql, String.class, databaseName, tableName);
        log.debug("表注释查询完成 databaseName={}, tableName={}, tableComment={}", databaseName, tableName, tableComment);

        String columnSql = """
                SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """;

        List<Map<String, Object>> columnRows = primaryJdbcTemplate.queryForList(columnSql, databaseName, tableName);
        log.info("字段结构查询完成 databaseName={}, tableName={}, columnCount={}", databaseName, tableName, columnRows.size());

        List<ColumnSchema> columns = new ArrayList<>();
        for (Map<String, Object> row : columnRows) {
            ColumnSchema column = ColumnSchema.builder()
                    .columnName((String) row.get("COLUMN_NAME"))
                    .columnType((String) row.get("COLUMN_TYPE"))
                    .columnComment((String) row.get("COLUMN_COMMENT"))
                    .build();
            columns.add(column);
        }

        TableSchema schema = TableSchema.builder()
                .tableName(tableName)
                .tableComment(tableComment)
                .columns(columns)
                .build();
        log.info(
                "单表结构查询完成 databaseName={}, tableName={}, columnCount={}, costMs={}",
                databaseName, tableName, columns.size(), System.currentTimeMillis() - startTime
        );
        return schema;
    }

    @Override
    public String getFormattedTableSchema(String databaseName, String tableName) {
        long startTime = System.currentTimeMillis();
        TableSchema schema = getTableSchema(databaseName, tableName);
        String formattedSchema = schema != null ? schema.toFormattedString() : "";
        log.info(
                "格式化表结构完成 databaseName={}, tableName={}, textLength={}, costMs={}",
                databaseName, tableName, formattedSchema.length(), System.currentTimeMillis() - startTime
        );
        return formattedSchema;
    }

    private List<String> getAllTableNames(String databaseName) {
        long startTime = System.currentTimeMillis();
        String sql = "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'";
        log.debug("执行表名查询 databaseName={}, sql={}", databaseName, sql);
        List<String> tableNames = primaryJdbcTemplate.queryForList(sql, String.class, databaseName);
        log.info("表名查询完成 databaseName={}, tableCount={}, costMs={}", databaseName, tableNames.size(), System.currentTimeMillis() - startTime);
        return tableNames;
    }
}
