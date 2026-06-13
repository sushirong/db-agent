# -*- coding: utf-8 -*-
"""
集成测试脚本
模拟完整的 RAG + SQL 生成 + Java 沙箱执行 + 结果返回闭环链路
"""

import json
import sys
import io

# 设置标准输出编码为 UTF-8
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

import requests

# 服务地址
PYTHON_SERVICE_URL = "http://localhost:8000"
JAVA_SERVICE_URL = "http://localhost:8080"


def print_separator(title: str):
    """打印分隔线"""
    print("\n" + "=" * 60)
    print(f"  {title}")
    print("=" * 60)


def test_health_check():
    """测试服务健康状态"""
    print_separator("1. 服务健康检查")

    # 检查 Python 服务
    try:
        resp = requests.get(f"{PYTHON_SERVICE_URL}/", timeout=5)
        print(f"[Python 服务] {resp.json()}")
    except Exception as e:
        print(f"[Python 服务] 连接失败: {e}")
        return False

    # 检查 Java 服务
    try:
        resp = requests.get(f"{JAVA_SERVICE_URL}/", timeout=5)
        print(f"[Java 服务] 状态正常")
    except Exception as e:
        print(f"[Java 服务] 连接失败: {e} (可选，Python 端仍可测试)")

    return True


def test_schema_sync():
    """测试表结构同步"""
    print_separator("2. 表结构同步测试")

    # 模拟多张表的结构数据
    test_schemas = [
        {
            "tableName": "users",
            "tableComment": "用户信息表",
            "schemaText": "表名: users (用户信息表)\n列:\n  - id BIGINT (用户ID，主键)\n  - username VARCHAR(50) (用户名)\n  - email VARCHAR(100) (邮箱地址)\n  - phone VARCHAR(20) (手机号)\n  - status TINYINT (状态: 0-禁用, 1-启用)\n  - created_at DATETIME (创建时间)\n  - updated_at DATETIME (更新时间)"
        },
        {
            "tableName": "orders",
            "tableComment": "订单表",
            "schemaText": "表名: orders (订单表)\n列:\n  - id BIGINT (订单ID，主键)\n  - order_no VARCHAR(32) (订单编号)\n  - user_id BIGINT (用户ID，关联users表)\n  - total_amount DECIMAL(10,2) (订单总金额)\n  - status TINYINT (订单状态: 0-待支付, 1-已支付, 2-已发货, 3-已完成, 4-已取消)\n  - created_at DATETIME (下单时间)\n  - paid_at DATETIME (支付时间)"
        },
        {
            "tableName": "products",
            "tableComment": "商品表",
            "schemaText": "表名: products (商品表)\n列:\n  - id BIGINT (商品ID，主键)\n  - name VARCHAR(200) (商品名称)\n  - category_id BIGINT (分类ID)\n  - price DECIMAL(10,2) (商品价格)\n  - stock INT (库存数量)\n  - status TINYINT (状态: 0-下架, 1-上架)\n  - created_at DATETIME (创建时间)"
        },
        {
            "tableName": "order_items",
            "tableComment": "订单明细表",
            "schemaText": "表名: order_items (订单明细表)\n列:\n  - id BIGINT (明细ID，主键)\n  - order_id BIGINT (订单ID，关联orders表)\n  - product_id BIGINT (商品ID，关联products表)\n  - quantity INT (购买数量)\n  - unit_price DECIMAL(10,2) (商品单价)\n  - subtotal DECIMAL(10,2) (小计金额)"
        }
    ]

    success_count = 0
    for schema in test_schemas:
        try:
            resp = requests.post(
                f"{PYTHON_SERVICE_URL}/ai/schema/sync",
                json=schema,
                timeout=10
            )
            result = resp.json()
            status = "[OK]" if result.get("success") else "[FAIL]"
            print(f"  {status} 表 [{schema['tableName']}] - {result.get('message', '')}")
            if result.get("success"):
                success_count += 1
        except Exception as e:
            print(f"  [FAIL] 表 [{schema['tableName']}] 同步失败: {e}")

    print(f"\n同步结果: {success_count}/{len(test_schemas)} 成功")

    # 查看已同步的表
    try:
        resp = requests.get(f"{PYTHON_SERVICE_URL}/ai/schema/list", timeout=5)
        tables_info = resp.json()
        print(f"当前已同步表数量: {tables_info.get('count', 0)}")
    except Exception as e:
        print(f"获取表列表失败: {e}")

    return success_count > 0


def test_agent_query():
    """测试 Agent 智能查询"""
    print_separator("3. Agent 智能查询测试")

    # 测试用例列表
    test_queries = [
        "查询所有状态为启用的用户数量",
        "查询最近7天的订单总金额",
        "查询销量最好的前5个商品",
        "查询每个用户的订单数量，按订单数量降序排列"
    ]

    for i, query in enumerate(test_queries, 1):
        print(f"\n--- 测试用例 {i} ---")
        print(f"用户问题: {query}")

        try:
            resp = requests.post(
                f"{PYTHON_SERVICE_URL}/ai/agent/query",
                json={"query": query},
                timeout=180  # LLM 调用可能较慢，增加到 3 分钟
            )
            result = resp.json()

            print(f"执行状态: {'成功' if result.get('success') else '失败'}")
            print(f"生成的 SQL: {result.get('generated_sql', 'N/A')}")
            print(f"重试次数: {result.get('retry_count', 0)}")

            if result.get("success"):
                print(f"业务回答: {result.get('answer', 'N/A')}")
                data = result.get("data", [])
                if data:
                    print(f"查询结果: 返回 {len(data)} 条记录")
                    # 只显示前 3 条
                    for row in data[:3]:
                        print(f"  - {row}")
                    if len(data) > 3:
                        print(f"  ... 还有 {len(data) - 3} 条记录")
            else:
                print(f"错误信息: {result.get('error', 'N/A')}")

        except requests.exceptions.Timeout:
            print("请求超时 (60秒)，请检查 LLM 服务配置")
        except Exception as e:
            print(f"请求失败: {e}")


def test_sql_sandbox():
    """测试 SQL 沙箱安全机制"""
    print_separator("4. SQL 沙箱安全测试")

    # 测试危险 SQL 拦截
    dangerous_sqls = [
        ("INSERT INTO users VALUES (1, 'test')", "INSERT 操作"),
        ("DELETE FROM users WHERE id = 1", "DELETE 操作"),
        ("DROP TABLE users", "DROP 操作"),
        ("UPDATE users SET status = 0", "UPDATE 操作"),
    ]

    print("测试危险 SQL 拦截:")
    for sql, desc in dangerous_sqls:
        try:
            resp = requests.post(
                f"{JAVA_SERVICE_URL}/api/internal/db/execute",
                json={"sql": sql},
                timeout=5
            )
            result = resp.json()
            blocked = not result.get("success")
            status = "[BLOCKED]" if blocked else "[NOT BLOCKED]"
            print(f"  {status} [{desc}]")
        except Exception as e:
            print(f"  [ERROR] [{desc}] 无法连接 Java 服务: {e}")

    # 测试正常 SELECT
    print("\n测试正常 SELECT 查询:")
    try:
        resp = requests.post(
            f"{JAVA_SERVICE_URL}/api/internal/db/execute",
            json={"sql": "SELECT 1 AS test"},
            timeout=5
        )
        result = resp.json()
        print(f"  [{'OK' if result.get('success') else 'FAIL'}] SELECT 查询: {result}")
    except Exception as e:
        print(f"  [ERROR] 无法连接 Java 服务: {e}")


def run_all_tests():
    """运行所有测试"""
    print_separator("数据库智能查询 Agent - 集成测试")

    # 1. 健康检查
    if not test_health_check():
        print("\n[错误] Python 服务未启动，请先运行: uvicorn main:app --reload --port 8000")
        sys.exit(1)

    # 2. 表结构同步
    if not test_schema_sync():
        print("\n[警告] 表结构同步失败，Agent 查询测试可能无法正常工作")

    # 3. SQL 沙箱安全测试
    test_sql_sandbox()

    # 4. Agent 查询测试
    test_agent_query()

    # 测试完成
    print_separator("测试完成")
    print("所有测试用例执行完毕。")


if __name__ == "__main__":
    run_all_tests()
