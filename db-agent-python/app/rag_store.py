"""
RAG 知识库模块
使用 FAISS 存储和检索表结构向量
"""

import hashlib
import os
import json
import logging
import time
from typing import Optional

import faiss
import numpy as np
from langchain_community.embeddings import HuggingFaceEmbeddings

logger = logging.getLogger(__name__)

# 全局变量
_embeddings: Optional[HuggingFaceEmbeddings] = None
_index: Optional[faiss.IndexFlatIP] = None
_documents: list[dict] = []  # 存储文档和元数据
_persist_dir: str = "./faiss_data"


def get_embeddings() -> HuggingFaceEmbeddings:
    """获取 Embeddings 模型 (使用本地 HuggingFace 模型)"""
    global _embeddings
    if _embeddings is None:
        start_time = time.perf_counter()
        model_name = os.getenv("EMBEDDING_MODEL", "shibing624/text2vec-base-chinese")
        logger.info("开始加载 Embedding 模型 modelName=%s, device=cpu", model_name)
        _embeddings = HuggingFaceEmbeddings(
            model_name=model_name,
            model_kwargs={"device": "cpu"},
            encode_kwargs={"normalize_embeddings": True}
        )
        logger.info(
            "Embedding 模型加载完成 modelName=%s, costMs=%d",
            model_name,
            int((time.perf_counter() - start_time) * 1000)
        )
    return _embeddings


def _get_persist_dir() -> str:
    """获取持久化目录"""
    global _persist_dir
    _persist_dir = os.getenv("FAISS_PERSIST_DIR", "./faiss_data")
    os.makedirs(_persist_dir, exist_ok=True)
    logger.debug("FAISS 持久化目录确认完成 persistDir=%s", os.path.abspath(_persist_dir))
    return _persist_dir


def _save_to_disk():
    """保存索引和文档到磁盘"""
    global _index, _documents
    if _index is None:
        logger.warning("FAISS 索引为空，跳过持久化保存")
        return

    start_time = time.perf_counter()
    persist_dir = _get_persist_dir()
    index_path = os.path.join(persist_dir, "schema.index")
    docs_path = os.path.join(persist_dir, "documents.json")

    faiss.write_index(_index, index_path)
    with open(docs_path, "w", encoding="utf-8") as f:
        json.dump(_documents, f, ensure_ascii=False, indent=2)
    logger.info(
        "FAISS 数据持久化完成 indexPath=%s, docsPath=%s, indexCount=%d, documentCount=%d, costMs=%d",
        os.path.abspath(index_path),
        os.path.abspath(docs_path),
        _index.ntotal,
        len(_documents),
        int((time.perf_counter() - start_time) * 1000)
    )


def _load_from_disk():
    """从磁盘加载索引和文档"""
    global _index, _documents

    start_time = time.perf_counter()
    persist_dir = _get_persist_dir()
    index_path = os.path.join(persist_dir, "schema.index")
    docs_path = os.path.join(persist_dir, "documents.json")

    if os.path.exists(index_path) and os.path.exists(docs_path):
        _index = faiss.read_index(index_path)
        with open(docs_path, "r", encoding="utf-8") as f:
            _documents = json.load(f)
        logger.info(
            "FAISS 数据加载完成 indexPath=%s, docsPath=%s, indexCount=%d, documentCount=%d, costMs=%d",
            os.path.abspath(index_path),
            os.path.abspath(docs_path),
            _index.ntotal,
            len(_documents),
            int((time.perf_counter() - start_time) * 1000)
        )
    else:
        _index = faiss.IndexFlatIP(768)  # text2vec-base-chinese 输出 768 维
        _documents = []
        logger.info(
            "FAISS 本地数据不存在，初始化空索引 persistDir=%s, dimension=768, costMs=%d",
            os.path.abspath(persist_dir),
            int((time.perf_counter() - start_time) * 1000)
        )


def get_index() -> faiss.IndexFlatIP:
    """获取 FAISS 索引"""
    global _index
    if _index is None:
        logger.info("FAISS 索引尚未加载，准备从磁盘加载")
        _load_from_disk()
    return _index


def add_schema(tableName: str, schemaText: str, tableComment: Optional[str] = None) -> bool:
    """
    将单张表的结构文本向量化并存入 FAISS

    Args:
        tableName: 表名
        schemaText: 格式化的表结构文本
        tableComment: 表注释 (可选)

    Returns:
        是否成功
    """
    global _index, _documents

    start_time = time.perf_counter()
    logger.info(
        "开始写入表结构向量 tableName=%s, tableComment=%s, schemaTextLength=%d",
        tableName,
        tableComment,
        len(schemaText or "")
    )

    try:
        embeddings = get_embeddings()
        index = get_index()

        embedding_start_time = time.perf_counter()
        embedding_vector = embeddings.embed_query(schemaText)
        embedding_array = np.array([embedding_vector], dtype=np.float32)
        logger.info(
            "表结构向量生成完成 tableName=%s, dimension=%d, costMs=%d",
            tableName,
            len(embedding_vector),
            int((time.perf_counter() - embedding_start_time) * 1000)
        )

        existing_idx = None
        for i, doc in enumerate(_documents):
            if doc["tableName"] == tableName:
                existing_idx = i
                break

        if existing_idx is not None:
            _documents[existing_idx] = {
                "tableName": tableName,
                "tableComment": tableComment or "",
                "schemaText": schemaText
            }
            logger.info("表结构已存在，准备重建 FAISS 索引 tableName=%s, existingIndex=%d", tableName, existing_idx)
            _rebuild_index()
        else:
            index.add(embedding_array)
            _documents.append({
                "tableName": tableName,
                "tableComment": tableComment or "",
                "schemaText": schemaText
            })
            logger.info(
                "新表结构向量添加完成 tableName=%s, indexCount=%d, documentCount=%d",
                tableName,
                index.ntotal,
                len(_documents)
            )

        _save_to_disk()

        logger.info(
            "表结构向量写入完成 tableName=%s, documentCount=%d, costMs=%d",
            tableName,
            len(_documents),
            int((time.perf_counter() - start_time) * 1000)
        )
        return True
    except Exception as e:
        logger.exception(
            "表结构向量写入失败 tableName=%s, schemaTextLength=%d",
            tableName,
            len(schemaText or "")
        )
        return False


def _rebuild_index():
    """重建 FAISS 索引，用于文档更新场景"""
    global _index, _documents

    start_time = time.perf_counter()
    logger.info("开始重建 FAISS 索引 documentCount=%d", len(_documents))

    if not _documents:
        _index = faiss.IndexFlatIP(768)
        logger.info("FAISS 索引重建为空索引 dimension=768")
        return

    embeddings = get_embeddings()
    vectors = []

    for doc in _documents:
        vector = embeddings.embed_query(doc["schemaText"])
        vectors.append(vector)

    embedding_array = np.array(vectors, dtype=np.float32)
    _index = faiss.IndexFlatIP(768)
    _index.add(embedding_array)
    logger.info(
        "FAISS 索引重建完成 indexCount=%d, dimension=%d, costMs=%d",
        _index.ntotal,
        len(vectors[0]) if vectors else 0,
        int((time.perf_counter() - start_time) * 1000)
    )


def search_relevant_tables(query: str, top_k: int = 5) -> list[str]:
    """
    根据自然语言查询检索最相关的表结构

    Args:
        query: 用户的自然语言查询
        top_k: 返回最相关的表数量

    Returns:
        相关表的结构文本列表
    """
    try:
        start_time = time.perf_counter()
        logger.info("开始检索相关表结构 query=%s, topK=%d", query, top_k)
        index = get_index()

        if index.ntotal == 0:
            logger.warning("FAISS 索引为空，无法检索 query=%s", query)
            return []

        embeddings = get_embeddings()

        embedding_start_time = time.perf_counter()
        query_embedding = embeddings.embed_query(query)
        query_array = np.array([query_embedding], dtype=np.float32)
        logger.info(
            "查询向量生成完成 query=%s, dimension=%d, costMs=%d",
            query,
            len(query_embedding),
            int((time.perf_counter() - embedding_start_time) * 1000)
        )

        k = min(top_k, index.ntotal)
        scores, indices = index.search(query_array, k)

        results = []
        matched_tables = []
        for score, idx in zip(scores[0], indices[0]):
            if 0 <= idx < len(_documents):
                matched_tables.append({
                    "tableName": _documents[idx]["tableName"],
                    "score": float(score)
                })
                results.append(_documents[idx]["schemaText"])

        logger.info(
            "相关表结构检索完成 query=%s, matchedCount=%d, matchedTables=%s, costMs=%d",
            query,
            len(results),
            matched_tables,
            int((time.perf_counter() - start_time) * 1000)
        )
        return results

    except Exception as e:
        logger.exception("相关表结构检索失败 query=%s, topK=%d", query, top_k)
        return []


def get_all_tables() -> list[dict]:
    """获取所有已存储的表信息"""
    logger.info("获取已存储表结构列表 documentCount=%d", len(_documents))
    return [
        {
            "tableName": doc["tableName"],
            "metadata": {"tableComment": doc.get("tableComment", "")},
            "document": doc["schemaText"]
        }
        for doc in _documents
    ]


def get_tables_metadata() -> list[dict]:
    """
    获取所有已存储表的轻量元数据（表名 + schemaText 哈希）
    用于增量同步时对比差异
    """
    result = []
    for doc in _documents:
        schema_hash = hashlib.md5(doc["schemaText"].encode("utf-8")).hexdigest()
        result.append({
            "tableName": doc["tableName"],
            "schemaHash": schema_hash
        })
    logger.info("获取表元数据完成 count=%d", len(result))
    return result


def remove_schema(tableName: str) -> bool:
    """
    从 FAISS 中移除指定表的结构数据

    Args:
        tableName: 要移除的表名

    Returns:
        是否成功
    """
    global _index, _documents

    try:
        existing_idx = None
        for i, doc in enumerate(_documents):
            if doc["tableName"] == tableName:
                existing_idx = i
                break

        if existing_idx is None:
            logger.warning("移除表结构失败，表不存在 tableName=%s", tableName)
            return False

        _documents.pop(existing_idx)
        _rebuild_index()
        _save_to_disk()
        logger.info("表结构移除成功 tableName=%s, remainingCount=%d", tableName, len(_documents))
        return True
    except Exception:
        logger.exception("移除表结构异常 tableName=%s", tableName)
        return False


def clear_all() -> bool:
    """清空所有表结构数据"""
    global _index, _documents

    try:
        start_time = time.perf_counter()
        logger.warning("准备清空 FAISS 表结构数据 currentDocumentCount=%d", len(_documents))
        _index = faiss.IndexFlatIP(768)
        _documents = []
        _save_to_disk()
        logger.info("FAISS 表结构数据清空完成 costMs=%d", int((time.perf_counter() - start_time) * 1000))
        return True
    except Exception as e:
        logger.exception("FAISS 表结构数据清空失败")
        return False
