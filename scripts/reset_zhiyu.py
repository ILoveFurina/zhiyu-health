# -*- coding: utf-8 -*-
"""开发期约定 drop + recreate + seed 云演示库 zhiyu。
目标库名硬断言为 zhiyu；绝不触碰 zhiyu_it / zhiyu_test。
schema.sql + seed.sql 各自包在单事务内执行（失败即整批回滚，库保持空，便于排查重试）。
绝不打印任何连接凭据。"""
import os
import re
import sys
import traceback
import psycopg

sys.stdout.reconfigure(encoding="utf-8")

TARGET = "zhiyu"

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
host = m.group(1); port = int(m.group(2) or 5432)

# 只允许重建演示库 zhiyu
if TARGET != "zhiyu":
    print(f"ABORT: 目标库名必须是 zhiyu，实际 {TARGET}")
    sys.exit(1)

def step(msg):
    print(f"\n== {msg}")

def read_sql(name):
    p = os.path.join(ROOT, "server-java", "src", "main", "resources", name)
    if not os.path.exists(p):
        print(f"FAIL: 找不到 {p}")
        sys.exit(1)
    with open(p, "r", encoding="utf-8") as f:
        return f.read()

# ---------- 阶段 1：DROP + CREATE ----------
step(f"DROP DATABASE {TARGET} WITH (FORCE) + CREATE DATABASE {TARGET}")
try:
    conn = psycopg.connect(host=host, port=port, dbname="postgres",
                           user=user, password=password, connect_timeout=15)
    conn.autocommit = True
    conn.execute(f'DROP DATABASE IF EXISTS "{TARGET}" WITH (FORCE);')
    print(f"DROP {TARGET}: ok")
    conn.execute(f'CREATE DATABASE "{TARGET}";')
    print(f"CREATE {TARGET}: ok")
    conn.close()
except Exception:
    print("FAIL at drop/create:")
    traceback.print_exc()
    sys.exit(1)

# ---------- 阶段 2：schema.sql ----------
schema = read_sql("schema.sql")
step(f"apply schema.sql to {TARGET}")
try:
    conn = psycopg.connect(host=host, port=port, dbname=TARGET,
                           user=user, password=password, connect_timeout=15)
    conn.execute(schema)
    conn.commit()
    print("schema.sql: applied ok")
    conn.close()
except Exception:
    print("FAIL applying schema.sql (DB left empty, safe to re-run whole script):")
    traceback.print_exc()
    sys.exit(1)

# ---------- 阶段 3：seed.sql ----------
seed = read_sql("seed.sql")
step(f"apply seed.sql to {TARGET}")
try:
    conn = psycopg.connect(host=host, port=port, dbname=TARGET,
                           user=user, password=password, connect_timeout=15)
    conn.execute(seed)
    conn.commit()
    print("seed.sql: applied ok")
    conn.close()
except Exception:
    print("FAIL applying seed.sql (transaction rolled back; re-run whole script):")
    traceback.print_exc()
    sys.exit(1)

print("\n== drop+recreate+seed 完成")
