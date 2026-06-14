"""
Agent 核心工作流模块
实现 Text-to-SQL 的完整流程：RAG 检索 -> SQL 生成 -> 执行 -> 自我纠错 -> 结果总结
"""

import json
import logging
import os
import time
import uuid
from typing import Iterator, Optional

import requests
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, SystemMessage

from app import rag_store
from app.models import ChatMessage, SqlExecutionResponse

logger = logging.getLogger(__name__)

# 最大重试次数
MAX_RETRY_COUNT = 3

# Java 服务地址
JAVA_SERVICE_URL = os.getenv("JAVA_SERVICE_URL", "http://localhost:8080")

# 系统 Prompt：定义 Agent 角色
SYSTEM_PROMPT = """你是一个高级数据库 DBA 和 SQL 专家。你的任务是根据用户的问题和提供的数据库表结构，生成准确的 SQL 查询语句。

规则：
1. 只输出 SQL 语句，不要输出任何其他内容
2. 只生成 SELECT 查询语句，禁止生成 INSERT/UPDATE/DELETE/DROP 等写操作
3. 仔细分析表结构中的列名和注释，选择正确的字段
4. 如果需要多表关联，使用正确的 JOIN 语法
5. 注意处理 NULL 值和数据类型
6. 对于模糊查询使用 LIKE 操作符
7. 合理使用聚合函数 (COUNT, SUM, AVG, MAX, MIN)
8. 如果用户问题不明确，生成最可能的查询
9. 当存在对话历史时，判断当前问题是否与之前的查询相关。如果相关（如追问、补充、细化），沿用之前 SQL 涉及的表和条件；如果不相关（话题已切换），则独立分析"""

# SQL 生成 Prompt 模板
SQL_GENERATION_PROMPT = """根据以下表结构信息和用户问题，生成 SQL 查询语句。

## 相关表结构：
{schema_context}
{history_section}## 用户问题：
{query}

请直接输出 SQL 语句："""

# SQL 修正 Prompt 模板
SQL_FIX_PROMPT = """之前的 SQL 执行出错，请根据错误信息修正 SQL。

## 原始 SQL：
{original_sql}

## 错误信息：
{error_message}

## 相关表结构：
{schema_context}

请输出修正后的 SQL 语句："""

# 结果总结 Prompt 模板
RESULT_SUMMARY_PROMPT = """根据用户的问题和查询结果，生成简洁的中文业务回答。
{history_section}## 用户问题：
{query}

## 查询结果 (JSON 格式)：
{result_json}

请用简洁的中文回答用户的问题，突出关键数据："""


def get_llm() -> ChatOpenAI:
    """获取 LLM 客户端"""
    model_name = os.getenv("LLM_MODEL_NAME", "gpt-4")
    base_url = os.getenv("OPENAI_API_BASE", "https://api.openai.com/v1")
    logger.info("初始化 LLM 客户端 modelName=%s, baseUrl=%s, temperature=0", model_name, base_url)
    return ChatOpenAI(
        model=model_name,
        temperature=0,
        api_key=os.getenv("OPENAI_API_KEY"),
        base_url=base_url
    )


def abbreviate_text(text: Optional[str], max_length: int = 500) -> str:
    """
    截断日志文本
    长 SQL 和长 Prompt 仅记录前缀，避免日志体积过大
    """
    if not text:
        return ""
    normalized_text = " ".join(text.split())
    if len(normalized_text) <= max_length:
        return normalized_text
    return normalized_text[:max_length] + "..."


def clean_sql_response(content: str) -> str:
    """
    清理模型返回的 SQL 文本
    Markdown 代码块标记会被移除，便于 Java 沙箱直接执行
    """
    sql = content.strip()
    if sql.startswith("```sql"):
        sql = sql[6:]
    if sql.startswith("```"):
        sql = sql[3:]
    if sql.endswith("```"):
        sql = sql[:-3]
    return sql.strip()


def format_history_for_prompt(history: Optional[list[ChatMessage]], max_messages: int = 10) -> str:
    """
    将对话历史格式化为 Prompt 可读文本
    截取最近 N 条消息，避免 token 过长
    """
    if not history:
        return ""

    recent = history[-max_messages:]
    lines = []
    for msg in recent:
        role_label = "用户" if msg.role == "user" else "助手"
        # 截断过长的回答，保留关键信息
        content = msg.content[:500] + "..." if len(msg.content) > 500 else msg.content
        lines.append(f"{role_label}：{content}")

    return "## 对话历史：\n" + "\n".join(lines) + "\n\n"


def retrieve_schema_context(query: str, history: Optional[list[ChatMessage]] = None) -> str:
    """
    使用 RAG 检索相关表结构
    有对话历史时，将上下文融入检索查询以提高准确度

    Args:
        query: 用户查询
        history: 对话历史

    Returns:
        拼接完成的表结构上下文
    """
    start_time = time.perf_counter()

    # 有历史时，将最近的用户问题拼接到查询中，帮助 RAG 检索到正确的表
    search_query = query
    if history:
        recent_user_messages = [m.content for m in history if m.role == "user"][-3:]
        if recent_user_messages:
            search_query = " ".join(recent_user_messages) + " " + query

    logger.info("开始检索 Schema 上下文 query=%s, searchQuery=%s", query, abbreviate_text(search_query, 200))
    relevant_schemas = rag_store.search_relevant_tables(search_query, top_k=5)

    if not relevant_schemas:
        logger.warning("Schema 上下文检索为空 query=%s", query)
        return "未找到相关表结构，请确保已同步表结构数据。"

    # 相关表结构会拼接成模型可读的上下文文本
    context = "以下是与查询相关的数据库表结构：\n\n"
    for i, schema in enumerate(relevant_schemas, 1):
        context += f"--- 表 {i} ---\n{schema}\n\n"

    logger.info(
        "Schema 上下文检索完成 query=%s, schemaCount=%d, contextLength=%d, costMs=%d",
        query,
        len(relevant_schemas),
        len(context),
        int((time.perf_counter() - start_time) * 1000)
    )
    return context


def generate_sql(llm: ChatOpenAI, query: str, schema_context: str, history: Optional[list[ChatMessage]] = None) -> str:
    """
    调用 LLM 生成 SQL

    Args:
        llm: LLM 客户端
        query: 用户查询
        schema_context: 表结构上下文
        history: 对话历史

    Returns:
        生成的 SQL 语句
    """
    start_time = time.perf_counter()
    prompt = SQL_GENERATION_PROMPT.format(
        schema_context=schema_context,
        history_section=format_history_for_prompt(history),
        query=query
    )

    messages = [
        SystemMessage(content=SYSTEM_PROMPT),
        HumanMessage(content=prompt)
    ]

    logger.info(
        "开始调用 LLM 生成 SQL query=%s, historyCount=%d, schemaContextLength=%d, promptLength=%d",
        query,
        len(history or []),
        len(schema_context),
        len(prompt)
    )
    response = llm.invoke(messages)
    sql = clean_sql_response(response.content)
    logger.info(
        "LLM SQL 生成完成 query=%s, sqlLength=%d, sqlPreview=%s, costMs=%d",
        query,
        len(sql),
        abbreviate_text(sql),
        int((time.perf_counter() - start_time) * 1000)
    )
    return sql


def execute_sql_via_java(sql: str, databaseName: Optional[str] = None) -> SqlExecutionResponse:
    """
    调用 Java 端的 SQL 执行沙箱

    Args:
        sql: SQL 语句
        databaseName: 数据库名称 (可选)

    Returns:
        执行结果
    """
    url = f"{JAVA_SERVICE_URL}/api/internal/db/execute"
    payload = {"sql": sql}
    if databaseName:
        payload["databaseName"] = databaseName

    try:
        start_time = time.perf_counter()
        logger.info(
            "开始调用 Java SQL 沙箱 url=%s, databaseName=%s, sqlLength=%d, sqlPreview=%s",
            url,
            databaseName,
            len(sql or ""),
            abbreviate_text(sql)
        )
        response = requests.post(url, json=payload, timeout=30)
        response.raise_for_status()
        result = SqlExecutionResponse(**response.json())
        logger.info(
            "Java SQL 沙箱调用完成 success=%s, rowCount=%d, errorMessage=%s, costMs=%d",
            result.success,
            len(result.data or []),
            result.errorMessage,
            int((time.perf_counter() - start_time) * 1000)
        )
        return result
    except requests.exceptions.RequestException as e:
        logger.exception("Java SQL 沙箱调用失败 url=%s, databaseName=%s", url, databaseName)
        return SqlExecutionResponse(
            success=False,
            errorMessage=f"调用 Java 服务失败: {str(e)}"
        )


def fix_sql(llm: ChatOpenAI, original_sql: str, error_message: str, schema_context: str) -> str:
    """
    根据错误信息修正 SQL

    Args:
        llm: LLM 客户端
        original_sql: 原始 SQL
        error_message: 错误信息
        schema_context: 表结构上下文

    Returns:
        修正完成的 SQL
    """
    start_time = time.perf_counter()
    prompt = SQL_FIX_PROMPT.format(
        original_sql=original_sql,
        error_message=error_message,
        schema_context=schema_context
    )

    messages = [
        SystemMessage(content=SYSTEM_PROMPT),
        HumanMessage(content=prompt)
    ]

    logger.info(
        "开始调用 LLM 修正 SQL originalSqlPreview=%s, errorMessage=%s, promptLength=%d",
        abbreviate_text(original_sql),
        error_message,
        len(prompt)
    )
    response = llm.invoke(messages)
    fixed_sql = clean_sql_response(response.content)
    logger.info(
        "LLM SQL 修正完成 fixedSqlLength=%d, fixedSqlPreview=%s, costMs=%d",
        len(fixed_sql),
        abbreviate_text(fixed_sql),
        int((time.perf_counter() - start_time) * 1000)
    )
    return fixed_sql


def build_result_summary_messages(query: str, result_data: list, history: Optional[list[ChatMessage]] = None) -> list:
    """
    构建查询结果总结所需的消息列表
    查询结果过长时会截断，避免模型上下文过大
    """
    result_json = json.dumps(result_data, ensure_ascii=False, indent=2)

    if len(result_json) > 4000:
        logger.info("查询结果 JSON 超出限制，准备截断 query=%s, originalLength=%d", query, len(result_json))
        result_json = result_json[:4000] + "\n... (结果已截断)"

    prompt = RESULT_SUMMARY_PROMPT.format(
        history_section=format_history_for_prompt(history),
        query=query,
        result_json=result_json
    )

    return [
        SystemMessage(content="你是一个数据分析助手，用简洁的中文回答用户问题。"),
        HumanMessage(content=prompt)
    ]


def generate_answer(llm: ChatOpenAI, query: str, result_data: list, history: Optional[list[ChatMessage]] = None) -> str:
    """
    根据查询结果生成中文业务回答

    Args:
        llm: LLM 客户端
        query: 用户原始问题
        result_data: 查询结果数据
        history: 对话历史

    Returns:
        中文业务回答
    """
    start_time = time.perf_counter()
    logger.info("开始调用 LLM 生成业务回答 query=%s, historyCount=%d, rowCount=%d", query, len(history or []), len(result_data or []))
    response = llm.invoke(build_result_summary_messages(query, result_data, history))
    answer = response.content.strip()
    logger.info(
        "LLM 业务回答生成完成 query=%s, answerLength=%d, costMs=%d",
        query,
        len(answer),
        int((time.perf_counter() - start_time) * 1000)
    )
    return answer


def stream_answer(llm: ChatOpenAI, query: str, result_data: list, history: Optional[list[ChatMessage]] = None) -> Iterator[str]:
    """
    流式生成中文业务回答
    模型输出的文本片段会逐段返回给前端
    """
    start_time = time.perf_counter()
    chunk_count = 0
    total_length = 0
    logger.info("开始流式生成业务回答 query=%s, historyCount=%d, rowCount=%d", query, len(history or []), len(result_data or []))
    for chunk in llm.stream(build_result_summary_messages(query, result_data, history)):
        content = chunk.content
        if isinstance(content, str) and content:
            chunk_count += 1
            total_length += len(content)
            logger.debug("收到业务回答流式片段 query=%s, chunkIndex=%d, chunkLength=%d", query, chunk_count, len(content))
            yield content
    logger.info(
        "流式业务回答生成完成 query=%s, chunkCount=%d, totalLength=%d, costMs=%d",
        query,
        chunk_count,
        total_length,
        int((time.perf_counter() - start_time) * 1000)
    )


def run_agent_workflow(query: str, databaseName: Optional[str] = None, history: Optional[list[ChatMessage]] = None) -> dict:
    """
    执行完整的 Agent 工作流

    RAG 负责提供表结构上下文
    LLM 负责生成 SQL 和中文业务回答
    Java 沙箱负责执行只读 SQL
    SQL 执行失败时会使用错误信息修正 SQL

    Args:
        query: 用户的自然语言查询
        databaseName: 数据库名称 (可选)
        history: 对话历史

    Returns:
        包含完整结果的字典
    """
    trace_id = uuid.uuid4().hex[:12]
    start_time = time.perf_counter()
    logger.info("Agent 查询开始 traceId=%s, query=%s, historyCount=%d, databaseName=%s", trace_id, query, len(history or []), databaseName)
    llm = get_llm()
    retry_count = 0

    schema_context = retrieve_schema_context(query, history)

    current_sql = generate_sql(llm, query, schema_context, history)

    while retry_count <= MAX_RETRY_COUNT:
        logger.info(
            "Agent 准备执行 SQL traceId=%s, retryCount=%d, sqlPreview=%s",
            trace_id,
            retry_count,
            abbreviate_text(current_sql)
        )
        execution_result = execute_sql_via_java(current_sql, databaseName)

        if execution_result.success:
            answer = generate_answer(llm, query, execution_result.data or [], history)
            logger.info(
                "Agent 查询成功 traceId=%s, retryCount=%d, rowCount=%d, costMs=%d",
                trace_id,
                retry_count,
                len(execution_result.data or []),
                int((time.perf_counter() - start_time) * 1000)
            )

            return {
                "success": True,
                "query": query,
                "generated_sql": current_sql,
                "data": execution_result.data,
                "answer": answer,
                "error": None,
                "retry_count": retry_count
            }
        else:
            if retry_count < MAX_RETRY_COUNT:
                retry_count += 1
                logger.warning(
                    "Agent SQL 执行失败，准备修正 traceId=%s, retryCount=%d, errorMessage=%s",
                    trace_id,
                    retry_count,
                    execution_result.errorMessage
                )

                current_sql = fix_sql(
                    llm,
                    current_sql,
                    execution_result.errorMessage,
                    schema_context
                )
            else:
                logger.error(
                    "Agent 查询失败，达到最大重试次数 traceId=%s, retryCount=%d, errorMessage=%s, costMs=%d",
                    trace_id,
                    retry_count,
                    execution_result.errorMessage,
                    int((time.perf_counter() - start_time) * 1000)
                )
                return {
                    "success": False,
                    "query": query,
                    "generated_sql": current_sql,
                    "data": None,
                    "answer": None,
                    "error": f"SQL 执行失败 (已重试 {MAX_RETRY_COUNT} 次): {execution_result.errorMessage}",
                    "retry_count": retry_count
                }

    logger.error("Agent 查询进入未知失败分支 traceId=%s, retryCount=%d", trace_id, retry_count)
    return {
        "success": False,
        "query": query,
        "generated_sql": current_sql,
        "data": None,
        "answer": None,
        "error": "未知错误",
        "retry_count": retry_count
    }


def run_agent_workflow_stream(query: str, databaseName: Optional[str] = None, history: Optional[list[ChatMessage]] = None) -> Iterator[dict]:
    """
    流式执行 Agent 查询工作流
    前端可以即时收到状态、SQL、数据和回答片段
    """
    trace_id = uuid.uuid4().hex[:12]
    start_time = time.perf_counter()
    logger.info("Agent 流式查询开始 traceId=%s, query=%s, historyCount=%d, databaseName=%s", trace_id, query, len(history or []), databaseName)
    llm = get_llm()
    retry_count = 0
    current_sql = None

    try:
        logger.info("Agent 流式阶段开始 traceId=%s, stage=retrieve_schema", trace_id)
        yield {
            "type": "status",
            "message": "正在检索相关表结构..."
        }
        schema_context = retrieve_schema_context(query, history)

        logger.info("Agent 流式阶段开始 traceId=%s, stage=generate_sql", trace_id)
        yield {
            "type": "status",
            "message": "正在生成 SQL..."
        }
        current_sql = generate_sql(llm, query, schema_context, history)
        logger.info("Agent 流式 SQL 生成完成 traceId=%s, sqlPreview=%s", trace_id, abbreviate_text(current_sql))
        yield {
            "type": "sql",
            "generated_sql": current_sql
        }

        while retry_count <= MAX_RETRY_COUNT:
            logger.info("Agent 流式阶段开始 traceId=%s, stage=execute_sql, retryCount=%d", trace_id, retry_count)
            yield {
                "type": "status",
                "message": "正在执行 SQL..."
            }
            execution_result = execute_sql_via_java(current_sql, databaseName)

            if execution_result.success:
                result_data = execution_result.data or []
                logger.info(
                    "Agent 流式 SQL 执行成功 traceId=%s, rowCount=%d, retryCount=%d",
                    trace_id,
                    len(result_data),
                    retry_count
                )
                yield {
                    "type": "data",
                    "data": result_data,
                    "retry_count": retry_count
                }

                yield {
                    "type": "answer_start"
                }
                answer_parts = []
                for chunk in stream_answer(llm, query, result_data, history):
                    answer_parts.append(chunk)
                    yield {
                        "type": "answer_delta",
                        "content": chunk
                    }

                answer = "".join(answer_parts).strip()
                logger.info(
                    "Agent 流式查询成功 traceId=%s, answerLength=%d, retryCount=%d, costMs=%d",
                    trace_id,
                    len(answer),
                    retry_count,
                    int((time.perf_counter() - start_time) * 1000)
                )
                yield {
                    "type": "done",
                    "success": True,
                    "query": query,
                    "generated_sql": current_sql,
                    "data": result_data,
                    "answer": answer,
                    "error": None,
                    "retry_count": retry_count
                }
                return

            if retry_count < MAX_RETRY_COUNT:
                retry_count += 1
                logger.warning(
                    "Agent 流式 SQL 执行失败，准备修正 traceId=%s, retryCount=%d, errorMessage=%s",
                    trace_id,
                    retry_count,
                    execution_result.errorMessage
                )
                yield {
                    "type": "status",
                    "message": f"SQL 执行失败，正在修正 SQL，重试次数：{retry_count}",
                    "error": execution_result.errorMessage
                }
                current_sql = fix_sql(
                    llm,
                    current_sql,
                    execution_result.errorMessage,
                    schema_context
                )
                logger.info(
                    "Agent 流式 SQL 修正完成 traceId=%s, retryCount=%d, sqlPreview=%s",
                    trace_id,
                    retry_count,
                    abbreviate_text(current_sql)
                )
                yield {
                    "type": "sql",
                    "generated_sql": current_sql
                }
            else:
                logger.error(
                    "Agent 流式查询失败，达到最大重试次数 traceId=%s, retryCount=%d, errorMessage=%s, costMs=%d",
                    trace_id,
                    retry_count,
                    execution_result.errorMessage,
                    int((time.perf_counter() - start_time) * 1000)
                )
                yield {
                    "type": "error",
                    "success": False,
                    "query": query,
                    "generated_sql": current_sql,
                    "data": None,
                    "answer": None,
                    "error": f"SQL 执行失败，重试次数：{MAX_RETRY_COUNT}，错误信息：{execution_result.errorMessage}",
                    "retry_count": retry_count
                }
                return

    except Exception as e:
        logger.exception(
            "Agent 流式查询异常 traceId=%s, query=%s, retryCount=%d, costMs=%d",
            trace_id,
            query,
            retry_count,
            int((time.perf_counter() - start_time) * 1000)
        )
        yield {
            "type": "error",
            "success": False,
            "query": query,
            "generated_sql": current_sql,
            "data": None,
            "answer": None,
            "error": f"Agent 执行异常: {str(e)}",
            "retry_count": retry_count
        }
