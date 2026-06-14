"""
表结构注释推断模块
当表/字段缺少中文注释时，使用 LLM 根据命名规律推断含义并补充
"""

import json
import logging
import re
import time

from langchain_core.messages import HumanMessage, SystemMessage

logger = logging.getLogger(__name__)

# LLM 推断缺失注释的 Prompt
ENRICH_SYSTEM_PROMPT = "你是一个数据库专家，擅长根据字段命名规律推断其业务含义。只输出要求格式的内容，不要任何多余文字。"

ENRICH_PROMPT_TEMPLATE = """以下是数据库表结构信息，其中部分表和字段缺少中文注释。
请根据字段名/表名的命名规律，推断它们的中文业务含义。

表名: {table_name}
表注释: {table_comment}

缺少注释的字段:
{missing_columns}

请以 JSON 格式返回推断结果:
- 如果表注释缺失，用 "{table_name}" 作为 key 推断表的中文含义
- 其余 key 为字段名，value 为中文含义
{{"{table_name}": "中文表名", "field_name": "中文含义", ...}}

只返回 JSON，不要其他内容。如果实在无法推断，给出合理的猜测。"""


def _parse_schema_text(schema_text: str) -> tuple[str, str, list[dict]]:
    """
    解析 schemaText，提取表名、表注释和列信息

    Returns:
        (table_name, table_comment, columns)
        columns 每项: {"name": str, "type": str, "comment": Optional[str], "has_comment": bool}
    """
    lines = schema_text.strip().split("\n")
    table_name = ""
    table_comment = ""
    columns = []

    for line in lines:
        line = line.strip()

        # 解析表名行: "表名: orders (订单表)" 或 "表名: orders"
        table_match = re.match(r"^表名:\s*(\S+)\s*(?:\((.+)\))?\s*$", line)
        if table_match:
            table_name = table_match.group(1)
            table_comment = table_match.group(2) or ""
            continue

        # 解析列行: "  - id BIGINT (用户ID)" 或 "  - id BIGINT"
        col_match = re.match(r"^-\s+(\S+)\s+(\S+)\s*(?:\((.+)\))?\s*$", line)
        if col_match:
            columns.append({
                "name": col_match.group(1),
                "type": col_match.group(2),
                "comment": col_match.group(3),
                "has_comment": col_match.group(3) is not None and col_match.group(3).strip() != ""
            })

    return table_name, table_comment, columns


def _build_enrich_prompt(table_name: str, table_comment: str, missing_columns: list[dict],
                         missing_table_comment: bool) -> str:
    """构造 LLM 推断 Prompt"""
    missing_lines = "\n".join(
        f"- {col['name']} ({col['type']})" for col in missing_columns
    )
    if not missing_columns:
        missing_lines = "（无缺失字段）"
    return ENRICH_PROMPT_TEMPLATE.format(
        table_name=table_name,
        table_comment=table_comment or "无",
        missing_columns=missing_lines
    )


def _call_llm_for_inference(prompt: str) -> dict[str, str]:
    """调用 LLM 推断缺失注释，返回 {字段名: 中文含义}"""
    from app.agent_workflow import get_llm

    llm = get_llm()
    messages = [
        SystemMessage(content=ENRICH_SYSTEM_PROMPT),
        HumanMessage(content=prompt)
    ]

    start_time = time.perf_counter()
    response = llm.invoke(messages)
    content = response.content.strip()

    logger.info(
        "LLM 注释推断调用完成 promptLength=%d, responseLength=%d, costMs=%d",
        len(prompt),
        len(content),
        int((time.perf_counter() - start_time) * 1000)
    )

    # 解析 JSON 响应
    # 处理可能的 markdown 代码块包裹
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?\s*", "", content)
        content = re.sub(r"\s*```$", "", content)

    try:
        return json.loads(content)
    except json.JSONDecodeError:
        logger.warning("LLM 返回的注释推断结果 JSON 解析失败，尝试提取 JSON content=%s", content[:200])
        # 尝试从响应中提取 JSON 部分
        json_match = re.search(r"\{.*\}", content, re.DOTALL)
        if json_match:
            try:
                return json.loads(json_match.group())
            except json.JSONDecodeError:
                pass
        logger.error("无法解析 LLM 注释推断结果")
        return {}


def _rebuild_schema_text(table_name: str, table_comment: str, columns: list[dict],
                         inferred: dict[str, str]) -> str:
    """
    重新构建 schemaText，为缺失注释的表/字段补充推断结果
    """
    # 表注释处理
    if table_comment:
        table_line = f"表名: {table_name} ({table_comment})"
    elif table_name.lower() in inferred:
        table_line = f"表名: {table_name} ([AI推断]{inferred[table_name.lower()]})"
    else:
        table_line = f"表名: {table_name}"

    lines = [table_line, "列:"]

    for col in columns:
        if col["has_comment"]:
            # 已有注释，保持原样
            lines.append(f"  - {col['name']} {col['type']} ({col['comment']})")
        else:
            # 缺失注释，尝试补充推断结果
            inferred_comment = inferred.get(col["name"])
            if inferred_comment:
                lines.append(f"  - {col['name']} {col['type']} ([AI推断]{inferred_comment})")
            else:
                lines.append(f"  - {col['name']} {col['type']}")

    return "\n".join(lines)


def enrich_schema_comments(schema_text: str) -> dict:
    """
    使用 LLM 推断并补充 schemaText 中缺失的中文注释

    如果所有表和字段都有注释，直接返回原文不做任何调用。

    Args:
        schema_text: Java 端传来的原始格式化表结构文本

    Returns:
        {"schemaText": 补充了推断注释的表结构文本, "tableComment": 推断的表注释或原表注释}
    """
    if not schema_text or not schema_text.strip():
        return {"schemaText": schema_text, "tableComment": ""}

    start_time = time.perf_counter()
    table_name, table_comment, columns = _parse_schema_text(schema_text)

    # 检查是否缺少注释
    missing_table_comment = not table_comment
    missing_columns = [col for col in columns if not col["has_comment"]]

    if not missing_table_comment and not missing_columns:
        logger.info("表结构注释完整，跳过 LLM 推断 tableName=%s", table_name)
        return {"schemaText": schema_text, "tableComment": table_comment}

    logger.info(
        "检测到缺失注释 tableName=%s, missingTableComment=%s, missingColumnCount=%d, totalColumnCount=%d",
        table_name,
        missing_table_comment,
        len(missing_columns),
        len(columns)
    )

    # 构造推断请求（包含表名和缺失注释的字段）
    missing_for_prompt = missing_columns.copy()
    if missing_table_comment:
        # 表名也作为需要推断的项，但不放在字段列表中，而是在 prompt 的表名处体现
        pass

    prompt = _build_enrich_prompt(table_name, table_comment, missing_for_prompt, missing_table_comment)

    # 调用 LLM 推断
    inferred = _call_llm_for_inference(prompt)

    # 如果表注释缺失且 LLM 返回了表名对应的推断
    if missing_table_comment and table_name and table_name.lower() not in inferred:
        # 尝试让 LLM 推断的表名映射（有些模型会返回 "表名" 作为 key）
        for key in ["table_name", "table_comment", table_name, "表名"]:
            if key in inferred:
                inferred[table_name.lower()] = inferred.pop(key)
                break

    # 重建 schemaText
    enriched_text = _rebuild_schema_text(table_name, table_comment, columns, inferred)

    # 解析最终的表注释（原始的或推断的）
    final_table_comment = table_comment
    if not final_table_comment and table_name.lower() in inferred:
        final_table_comment = inferred[table_name.lower()]

    logger.info(
        "表结构注释推断完成 tableName=%s, finalTableComment=%s, inferredCount=%d, costMs=%d",
        table_name,
        final_table_comment,
        len(inferred),
        int((time.perf_counter() - start_time) * 1000)
    )

    return {"schemaText": enriched_text, "tableComment": final_table_comment or ""}
