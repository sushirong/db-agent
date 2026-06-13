# 数据库智能查询 Agent (Text-to-SQL with RAG)

基于前端、Java、Python 三端协作的数据库智能查询 Agent 系统。用户在前端输入自然语言问题，Python 端结合 RAG 和 LLM 生成 SQL，Java 端提供表结构提取和只读 SQL 执行沙箱，最终将业务回答、生成的 SQL 和查询结果返回给前端展示。

## 项目结构

```text
.
├── db-agent-web/       # React + Vite 前端对话页面
├── db-agent-java/      # Spring Boot 数据源、表结构提取、SQL 沙箱服务
├── db-agent-python/    # FastAPI AI 编排、RAG 检索、LLM 生成 SQL 服务
├── init_tables.sql     # 测试业务库表结构和测试数据
├── .env.example        # 环境变量示例
└── README.md
```

## 三端职责

| 模块 | 职责 |
| --- | --- |
| 前端 `db-agent-web` | 提供聊天界面、发起自然语言查询、触发表结构同步、展示业务回答、SQL 和表格结果 |
| Java `db-agent-java` | 连接业务数据库、读取 `information_schema` 表结构、推送表结构给 Python、执行只读 SQL 查询 |
| Python `db-agent-python` | 接收表结构并写入 FAISS 向量库、检索相关表结构、调用 LLM 生成 SQL、失败时修正 SQL、总结中文业务回答 |

## 整体业务流程

```text
用户
  ↓
React 前端
  ↓  /api/python/ai/agent/query
Python FastAPI
  ↓  FAISS 检索相关表结构
Python RAG
  ↓  LLM 生成 SQL
Python Agent
  ↓  /api/internal/db/execute
Java SQL 沙箱
  ↓  只读数据源查询 MySQL
业务数据库
  ↑
Java 返回查询数据
  ↑
Python 生成中文业务回答
  ↑
前端展示回答、SQL、表格结果
```

## 表结构同步流程

表结构同步用于让 Python AI 服务了解数据库中的表、字段、字段类型和中文注释，是自然语言生成 SQL 的基础数据准备流程。

1. 用户在前端点击“同步最新表结构”。
2. 前端调用 Java 接口 `POST http://localhost:8080/api/schema/{databaseName}/sync`。
3. Java 通过主数据源查询 `information_schema.TABLES` 和 `information_schema.COLUMNS`。
4. Java 将每张表整理成包含表名、表注释、字段名、字段类型、字段注释的格式化文本。
5. Java 逐张表调用 Python 接口 `POST http://localhost:8000/ai/schema/sync`。
6. Python 使用 HuggingFace Embedding 将表结构文本转换成向量。
7. Python 将向量写入 FAISS，并将表结构文档持久化到 `db-agent-python/faiss_data/`。

同步完成后，Python 可以在用户提问时基于语义相似度检索相关表结构。

## 智能查询流程

智能查询用于将用户自然语言问题转换成 SQL，执行查询，并返回可读的中文业务回答。

1. 用户在前端聊天框输入问题，例如“查询销量最好的前 5 个商品”。
2. 前端调用 Python 查询接口 `/api/python/ai/agent/query`，Vite 将 `/api/python` 代理到 `http://localhost:8000`。
3. Python 接收用户问题后，从 FAISS 中检索最相关的表结构文本。
4. Python 将用户问题和相关表结构组合成 Prompt，调用 LLM 生成 SQL。
5. Python 调用 Java SQL 沙箱接口 `POST http://localhost:8080/api/internal/db/execute`。
6. Java 对 SQL 做安全检查，只允许 `SELECT` 或 `WITH` 查询，拦截写操作和危险关键字。
7. Java 使用只读数据源执行 SQL，并将查询结果返回给 Python。
8. SQL 执行失败时，Python 将错误信息、原 SQL 和表结构重新交给 LLM 修正 SQL，最多重试 3 次。
9. SQL 执行成功后，Python 将查询结果交给 LLM 生成简洁中文业务回答。
10. 前端展示 Python 返回的中文回答、生成的 SQL 和 Markdown 表格结果。

## 关键接口说明

| 接口 | 方向 | 说明 |
| --- | --- | --- |
| `POST /api/schema/{databaseName}/sync` | 前端 -> Java | 触发表结构同步，Java 提取业务库表结构并推送给 Python |
| `GET /api/schema/{databaseName}` | 前端/调试 -> Java | 获取指定数据库的所有表结构 |
| `POST /ai/schema/sync` | Java -> Python | 接收单张表结构，向量化后写入 FAISS |
| `GET /ai/schema/list` | 前端/调试 -> Python | 查看已同步到向量库的表结构列表 |
| `DELETE /ai/schema/clear` | 前端/调试 -> Python | 清空 Python 端已同步的表结构数据 |
| `POST /ai/agent/query` | 前端 -> Python | 提交自然语言问题，返回 SQL、查询数据和中文回答 |
| `POST /api/internal/db/execute` | Python -> Java | 执行 Python 生成的只读 SQL |

## 数据流说明

### 表结构数据流

```text
业务数据库 information_schema
  → Java DatabaseSchemaService
  → Java SchemaController
  → Python /ai/schema/sync
  → HuggingFace Embedding
  → FAISS 向量库
```

### 查询数据流

```text
用户问题
  → 前端 queryAgent
  → Python Agent
  → FAISS 表结构检索
  → LLM 生成 SQL
  → Java SQL 沙箱执行
  → 业务数据库查询结果
  → Python LLM 总结
  → 前端消息气泡展示
```

## 快速启动

### 启动 Java 服务

```bash
cd db-agent-java
mvn spring-boot:run
```

Java 服务默认运行在 `http://localhost:8080`。

### 启动 Python 服务

```bash
cd db-agent-python
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

Python 服务默认运行在 `http://localhost:8000`。

### 启动前端服务

```bash
cd db-agent-web
npm install
npm run dev
```

前端服务默认运行在 `http://localhost:3000`。

### 环境变量配置

复制 `.env.example` 为 `.env`，并填入实际配置：

```bash
cp .env.example .env
```

## 安全边界

Java SQL 沙箱只允许执行 `SELECT` 或 `WITH` 查询，并拦截 `INSERT`、`UPDATE`、`DELETE`、`DROP`、`TRUNCATE`、`ALTER`、`CREATE`、`REPLACE`、`GRANT`、`REVOKE` 等危险关键字。Python 不直接连接数据库，所有 SQL 执行都通过 Java 只读数据源完成。

## 当前实现注意点

- 前端自然语言查询通过 Vite 代理访问 Python 服务。
- 前端表结构同步直接访问 Java 服务 `http://localhost:8080`。
- Java SQL 执行请求模型中包含 `databaseName` 字段，但当前执行逻辑使用配置的数据源，不在请求中动态切换数据库。
- Python 端 FAISS 默认持久化目录为 `db-agent-python/faiss_data/`。
- LLM 调用耗时可能较长，前端请求超时时间设置为 3 分钟。
