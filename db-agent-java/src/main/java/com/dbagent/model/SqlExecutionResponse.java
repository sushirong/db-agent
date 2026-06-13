package com.dbagent.model;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SQL 执行响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlExecutionResponse {

    /** 是否执行成功 */
    private boolean success;

    /** 查询结果列表 (仅 SELECT 成功时有值) */
    private List<Map<String, Object>> data;

    /** 错误信息 (执行失败时有值) */
    private String errorMessage;

    /** 受影响的行数 (非 SELECT 语句) */
    private Integer affectedRows;

    public static SqlExecutionResponse success(List<Map<String, Object>> data) {
        return SqlExecutionResponse.builder()
                .success(true)
                .data(data)
                .build();
    }

    public static SqlExecutionResponse error(String errorMessage) {
        return SqlExecutionResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
