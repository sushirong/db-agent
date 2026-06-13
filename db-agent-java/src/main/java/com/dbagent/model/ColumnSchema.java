package com.dbagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 列结构元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnSchema {

    /** 列名 */
    private String columnName;

    /** 列类型 */
    private String columnType;

    /** 列注释 */
    private String columnComment;
}
