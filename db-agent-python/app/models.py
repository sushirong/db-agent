"""Pydantic 数据模型定义"""

from typing import Optional
from pydantic import BaseModel, Field


class SchemaSyncRequest(BaseModel):
    """表结构同步请求"""
    tableName: str = Field(..., description="表名")
    tableComment: Optional[str] = Field(None, description="表注释")
    schemaText: str = Field(..., description="格式化的表结构文本")


class SchemaSyncResponse(BaseModel):
    """表结构同步响应"""
    success: bool
    message: str


class AgentQueryRequest(BaseModel):
    """Agent 查询请求"""
    query: str = Field(..., description="用户的自然语言查询")
    databaseName: Optional[str] = Field(None, description="数据库名称")


class AgentQueryResponse(BaseModel):
    """Agent 查询响应"""
    success: bool
    query: str = Field(..., description="原始用户查询")
    generated_sql: Optional[str] = Field(None, description="生成的 SQL")
    data: Optional[list] = Field(None, description="查询结果数据")
    answer: Optional[str] = Field(None, description="最终中文业务回答")
    error: Optional[str] = Field(None, description="错误信息")
    retry_count: int = Field(0, description="重试次数")


class SqlExecutionRequest(BaseModel):
    """SQL 执行请求 (发送给 Java 端)"""
    sql: str
    databaseName: Optional[str] = None


class SqlExecutionResponse(BaseModel):
    """SQL 执行响应 (来自 Java 端)"""
    success: bool
    data: Optional[list] = None
    errorMessage: Optional[str] = None
    affectedRows: Optional[int] = None
