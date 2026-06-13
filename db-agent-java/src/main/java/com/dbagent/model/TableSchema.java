package com.dbagent.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 表结构元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableSchema {

    /** 表名 */
    private String tableName;

    /** 表注释 */
    private String tableComment;

    /** 列信息列表 */
    private List<ColumnSchema> columns;

    /**
     * 将表结构格式化为纯文本字符串
     * 格式示例:
     * 表名: users (用户信息表)
     * 列:
     *   - id BIGINT (用户ID)
     *   - username VARCHAR (用户名)
     */
    public String toFormattedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("表名: ").append(tableName);
        if (tableComment != null && !tableComment.isEmpty()) {
            sb.append(" (").append(tableComment).append(")");
        }
        sb.append("\n列:\n");

        for (ColumnSchema column : columns) {
            sb.append("  - ").append(column.getColumnName())
              .append(" ").append(column.getColumnType());
            if (column.getColumnComment() != null && !column.getColumnComment().isEmpty()) {
                sb.append(" (").append(column.getColumnComment()).append(")");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
