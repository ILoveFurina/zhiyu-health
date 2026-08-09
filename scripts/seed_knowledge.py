# -*- coding: utf-8 -*-
"""回填 knowledge_chunks.vector（离线向量，独立于 reset_zhiyu.py 的重建流程）。

seed-knowledge.sql 由 server-py/app/scripts/seed_embeddings.py 离线产出，
含 50 条 UPDATE ... SET vector = '[...]' WHERE id = N，被 .gitignore 忽略，仅本地存在。
本脚本从 .env 直连云演示库 zhiyu（与 reset_zhiyu.py 同源），单事务执行该文件：
失败即整体回滚，vector 保持回填前状态，可整批重跑。
绝不打印任何连接凭据。"""
import os
import re
import sys
import traceback

import psycopg

sys.stdout.reconfigure(encoding="utf-8")

TARGET = "zhiyu"
SQL_NAME = "seed-knowledge.sql"


def load_env(path):
    env = {}
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
env = load_env(os.path.join(ROOT, ".env"))
jdbc = env.get("DATABASE_JDBC_URL", "")
user = env.get("DATABASE_USER", "")
password = env.get("POSTGRES_PASSWORD", "")
m = re.match(r"jdbc:postgresql://([^:/]+):?(\d+)?/(\w+)", jdbc)
if not m:
    print("FAIL: 无法解析 DATABASE_JDBC_URL")
    sys.exit(1)
host = m.group(1)
port = int(m.group(2) or 5432)
dbname = m.group(3)

# 只允许回填演示库 zhiyu
if dbname != TARGET:
    print(f"ABORT: 目标库名必须是 zhiyu，实际 {dbname}")
    sys.exit(1)

sql_path = os.path.join(ROOT, "server-java", "src", "main", "resources", SQL_NAME)
if not os.path.exists(sql_path):
    print(f"FAIL: 找不到 {sql_path}")
    print("      该文件由离线 embedding 工具产出，请先运行：")
    print("      uv run python -m app.scripts.seed_embeddings")
    sys.exit(1)

with open(sql_path, "r", encoding="utf-8") as f:
    sql = f.read()

print(f"\n== 回填 {TARGET}.knowledge_chunks.vector（来源: {SQL_NAME}）")
try:
    conn = psycopg.connect(
        host=host, port=port, dbname=TARGET,
        user=user, password=password, connect_timeout=15,
    )
    try:
        conn.execute(sql)
        # 回填后校验：vector 非空行数应 == 总行数（50）
        cur = conn.execute("SELECT count(*) FROM knowledge_chunks")
        total = cur.fetchone()[0]
        cur = conn.execute(
            "SELECT count(*) FROM knowledge_chunks WHERE vector IS NOT NULL"
        )
        filled = cur.fetchone()[0]
        conn.commit()
        print(f"seed-knowledge.sql: applied ok（共 {total} 行，vector 已回填 {filled} 行）")
        if filled < total:
            print(f"WARN: 仍有 {total - filled} 行 vector 为 NULL，请核对 seed-knowledge.sql 覆盖范围")
    except Exception:
        conn.rollback()
        print("FAIL applying seed-knowledge.sql (transaction rolled back; re-run this script):")
        traceback.print_exc()
        sys.exit(1)
    finally:
        conn.close()
except Exception:
    print("FAIL connecting to database:")
    traceback.print_exc()
    sys.exit(1)

print("\n== vector 回填完成")
