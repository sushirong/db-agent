"""
FastAPI 主入口
提供表结构同步和 Agent 查询接口
"""

import json
import logging
import os
import time
import uuid
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from app import rag_store
from app.agent_workflow import run_agent_workflow, run_agent_workflow_stream
from app.models import (
    AgentQueryRequest,
    AgentQueryResponse,
    SchemaSyncRequest,
    SchemaSyncResponse,
)

# 加载环境变量
load_dotenv()

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s"
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    logger.info("数据库智能查询 Agent 服务启动中")
    logger.info("Java 服务地址=%s", os.getenv("JAVA_SERVICE_URL", "http://localhost:8080"))
    logger.info("LLM 模型=%s", os.getenv("LLM_MODEL_NAME", "gpt-4"))
    logger.info("Embedding 模型=%s", os.getenv("EMBEDDING_MODEL", "shibing624/text2vec-base-chinese"))
    logger.info("FAISS 持久化目录=%s", os.getenv("FAISS_PERSIST_DIR", "./faiss_data"))
    yield
    logger.info("数据库智能查询 Agent 服务关闭")


# 创建 FastAPI 应用
app = FastAPI(
    title="数据库智能查询 Agent",
    description="基于 RAG + LLM 的 Text-to-SQL 智能查询服务",
    version="1.0.0",
    lifespan=lifespan
)

# 配置跨域
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
async def root():
    """健康检查"""
    logger.info("收到健康检查请求")
    return {
        "service": "数据库智能查询 Agent",
        "status": "running",
        "version": "1.0.0"
    }


@app.post("/ai/schema/sync", response_model=SchemaSyncResponse)
async def sync_schema(request: SchemaSyncRequest):
    """
    同步表结构到 RAG 知识库

    接收 Java 端推送的表结构，进行向量化并存入 ChromaDB
    """
    start_time = time.perf_counter()
    logger.info(
        "收到表结构同步请求 tableName=%s, tableComment=%s, schemaTextLength=%d",
        request.tableName,
        request.tableComment,
        len(request.schemaText or "")
    )
    try:
        success = rag_store.add_schema(
            tableName=request.tableName,
            schemaText=request.schemaText,
            tableComment=request.tableComment
        )

        if success:
            logger.info(
                "表结构同步成功 tableName=%s, costMs=%d",
                request.tableName,
                int((time.perf_counter() - start_time) * 1000)
            )
            return SchemaSyncResponse(
                success=True,
                message=f"表 [{request.tableName}] 同步成功"
            )
        else:
            logger.error("表结构同步失败 tableName=%s", request.tableName)
            raise HTTPException(status_code=500, detail="表结构同步失败")

    except Exception as e:
        logger.exception(
            "表结构同步异常 tableName=%s, costMs=%d",
            request.tableName,
            int((time.perf_counter() - start_time) * 1000)
        )
        raise HTTPException(status_code=500, detail=f"同步异常: {str(e)}")


@app.get("/ai/schema/list")
async def list_schemas():
    """获取所有已同步的表结构列表"""
    start_time = time.perf_counter()
    logger.info("收到表结构列表查询请求")
    tables = rag_store.get_all_tables()
    logger.info(
        "表结构列表查询完成 count=%d, costMs=%d",
        len(tables),
        int((time.perf_counter() - start_time) * 1000)
    )
    return {
        "count": len(tables),
        "tables": [{"tableName": t["tableName"], "metadata": t["metadata"]} for t in tables]
    }


@app.delete("/ai/schema/clear")
async def clear_schemas():
    """清空所有表结构数据"""
    start_time = time.perf_counter()
    logger.warning("收到清空表结构数据请求")
    success = rag_store.clear_all()
    logger.info(
        "清空表结构数据完成 success=%s, costMs=%d",
        success,
        int((time.perf_counter() - start_time) * 1000)
    )
    return {"success": success, "message": "表结构数据已清空"}


@app.post("/ai/agent/query", response_model=AgentQueryResponse)
async def agent_query(request: AgentQueryRequest):
    """
    Agent 智能查询接口

    RAG 提供表结构上下文，LLM 生成 SQL，Java 沙箱执行只读查询
    """
    trace_id = uuid.uuid4().hex[:12]
    start_time = time.perf_counter()
    logger.info(
        "收到 Agent 非流式查询请求 traceId=%s, query=%s, databaseName=%s",
        trace_id,
        request.query,
        request.databaseName
    )
    try:
        result = run_agent_workflow(
            query=request.query,
            databaseName=request.databaseName
        )
        logger.info(
            "Agent 非流式查询完成 traceId=%s, success=%s, retryCount=%s, costMs=%d",
            trace_id,
            result.get("success"),
            result.get("retry_count"),
            int((time.perf_counter() - start_time) * 1000)
        )
        return AgentQueryResponse(**result)

    except Exception as e:
        logger.exception(
            "Agent 非流式查询异常 traceId=%s, query=%s, costMs=%d",
            trace_id,
            request.query,
            int((time.perf_counter() - start_time) * 1000)
        )
        return AgentQueryResponse(
            success=False,
            query=request.query,
            generated_sql=None,
            data=None,
            answer=None,
            error=f"Agent 执行异常: {str(e)}",
            retry_count=0
        )


@app.post("/ai/agent/query/stream")
async def agent_query_stream(request: AgentQueryRequest):
    """
    Agent 流式查询接口
    使用 SSE 持续推送查询状态、SQL、数据和回答片段
    """
    trace_id = uuid.uuid4().hex[:12]
    logger.info(
        "收到 Agent 流式查询请求 traceId=%s, query=%s, databaseName=%s",
        trace_id,
        request.query,
        request.databaseName
    )

    def event_stream():
        start_time = time.perf_counter()
        event_count = 0
        for event in run_agent_workflow_stream(
            query=request.query,
            databaseName=request.databaseName
        ):
            event_count += 1
            logger.info(
                "推送 Agent 流式事件 traceId=%s, eventIndex=%d, eventType=%s",
                trace_id,
                event_count,
                event.get("type")
            )
            event_json = json.dumps(event, ensure_ascii=False)
            yield f"data: {event_json}\n\n"
        logger.info(
            "Agent 流式查询响应结束 traceId=%s, eventCount=%d, costMs=%d",
            trace_id,
            event_count,
            int((time.perf_counter() - start_time) * 1000)
        )

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
