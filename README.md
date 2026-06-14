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

| 模块                       | 职责                                                            |
| ------------------------ | ------------------------------------------------------------- |
| 前端 `db-agent-web`        | 提供聊天界面、发起自然语言查询、触发表结构同步、展示业务回答、SQL 和表格结果                      |
| Java `db-agent-java`     | 连接业务数据库、读取 `information_schema` 表结构、推送表结构给 Python、执行只读 SQL 查询 |
| Python `db-agent-python` | 接收表结构并写入 FAISS 向量库、检索相关表结构、调用 LLM 生成 SQL、失败时修正 SQL、总结中文业务回答   |

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

表结构同步用于让 Python AI 服务了解数据库中的表、字段、字段类型和中文注释，是自然语言生成 SQL 的基础数据准备流程。支持增量同步和全量同步两种模式，同步过程中通过 SSE 实时推送进度。

### 同步模式

| 模式   | 触发方式                   | 说明                             |
| ---- | ---------------------- | ------------------------------ |
| 增量同步 | 左键点击”同步表结构”            | 仅同步新增表、结构变更的表，删除已不存在的表，跳过未变化的表 |
| 全量同步 | 右键点击”同步表结构” → 选择”全量同步” | 强制同步所有表结构，忽略哈希比对               |

### 增量同步流程

1. 用户在前端左键点击”同步表结构”按钮（默认增量同步）。
2. 前端通过 SSE 调用 Java 接口 `POST http://localhost:8080/api/schema/{databaseName}/sync`。
3. Java 通过主数据源查询 `information_schema.TABLES` 和 `information_schema.COLUMNS`，获取当前所有表结构。
4. Java 调用 Python 接口 `GET http://localhost:8000/ai/schema/metadata`，获取已存储表的元数据（表名 + MD5 哈希）。
5. Java 逐表计算 MD5 哈希比对，识别出新增表、结构变更表和已删除表。
6. Java 仅对变更的表逐张调用 Python 接口 `POST http://localhost:8000/ai/schema/sync`。
7. Java 对已删除的表调用 Python 接口 `DELETE http://localhost:8000/ai/schema/{tableName}`。
8. 每张表同步完成后，Java 通过 SSE 实时向前端推送进度事件。
9. Python 接收表结构后，先通过 LLM 推断缺失的表注释和字段注释（标记为 `[AI推断]`），再向量化写入 FAISS。

### LLM 注释推断

当数据库表缺少中文注释时，Python 端会调用 LLM 自动推断：

- 表注释缺失 → LLM 根据表名和字段推断用途
- 字段注释缺失 → LLM 根据字段名、类型和上下文推断含义
- 推断结果标记 `[AI推断]` 前缀，与真实注释区分
- 推断结果同时写入 `schemaText` 和 `tableComment`，确保向量检索和元数据一致

### 前端进度展示

同步过程中，前端通过 SSE 接收实时进度并在侧边栏展示：

- 进度条显示当前/总数
- 当前同步的表名
- 每张表的同步状态（成功/失败）
- 同步完成后 Toast 提示结果摘要

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

| 接口                                                 | 方向              | 说明                                    |
| -------------------------------------------------- | --------------- | ------------------------------------- |
| `POST /api/schema/{databaseName}/sync?force=false` | 前端 -> Java      | 触发表结构同步（SSE），默认增量同步，`force=true` 全量同步 |
| `GET /api/schema/{databaseName}`                   | 前端/调试 -> Java   | 获取指定数据库的所有表结构                         |
| `POST /ai/schema/sync`                             | Java -> Python  | 接收单表结构，LLM 推断缺失注释后向量化写入 FAISS         |
| `GET /ai/schema/metadata`                          | Java -> Python  | 获取已存储表的元数据（表名 + MD5 哈希），用于增量比对        |
| `DELETE /ai/schema/{tableName}`                    | Java -> Python  | 删除指定表的向量数据                            |
| `GET /ai/schema/list`                              | 前端/调试 -> Python | 查看已同步到向量库的表结构列表                       |
| `DELETE /ai/schema/clear`                          | 前端/调试 -> Python | 清空 Python 端已同步的表结构数据                  |
| `POST /ai/agent/query`                             | 前端 -> Python    | 提交自然语言问题，返回 SQL、查询数据和中文回答             |
| `POST /ai/agent/query/stream`                      | 前端 -> Python    | 流式提交自然语言问题（SSE），实时返回状态、SQL、数据和回答      |
| `POST /api/internal/db/execute`                    | Python -> Java  | 执行 Python 生成的只读 SQL                   |

## 数据流说明

### 表结构数据流（增量同步）

```text
业务数据库 information_schema
  → Java DatabaseSchemaService 获取当前表结构
  → Java SchemaSyncService 调用 Python /ai/schema/metadata 获取已存储哈希
  → Java MD5 哈希比对，识别新增/变更/删除
  → Python /ai/schema/sync（仅变更的表）
  → Python LLM 推断缺失注释
  → HuggingFace Embedding
  → FAISS 向量库
  → Java SSE 实时推送进度 → 前端进度条
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
