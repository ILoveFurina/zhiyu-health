# -*- coding: utf-8 -*-
"""只读验证重建后的 zhiyu：schema 形状、票55 新表、seed 行数与序列对齐。绝不打印凭据。"""
import os
import re
import sys
import psycopg

sys.stdout.reconfigure(encoding="utf-8")

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

env = load_env(os.path.join(os.path.dirname(__file__), "..", ".env"))
jdbc = env.get("DATABASE_JDBC_URL", "")
user = env.get("DATABASE_USER", "")
password = env.get("POSTGRES_PASSWORD", "")
m = re.match(r"jdbc:postgresql://([^:/]+):?(\d+)?/(\w+)", jdbc)
host = m.group(1); port = int(m.group(2) or 5432); db = m.group(3)

conn = psycopg.connect(host=host, port=port, dbname=db,
                       user=user, password=password, connect_timeout=15, autocommit=True)

def q(sql, params=None):
    return conn.execute(sql, params or ()).fetchall()

print("== 1. department_categories 列（院区化：campus_id，合并后规范形状）")
cols = [r[0] for r in q("SELECT column_name FROM information_schema.columns WHERE table_name='department_categories' ORDER BY ordinal_position")]
print("   ", cols)
assert "campus_id" in cols and "hospital_id" not in cols, "department_categories 形状不符合院区化约定"

print("== 2. 票55/票61 新表存在")
t54 = ["preconsultation_drafts", "online_consultations", "online_consultation_messages", "health_observations"]
tables = [r[0] for r in q("SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name")]
for t in t54:
    print(f"   {t}: {'OK' if t in tables else 'MISSING'}")
    assert t in tables, f"缺新表 {t}"

print("== 2b. 票56 双外键二选一形状（prescriptions/consultation_records）")
for t in ["prescriptions", "consultation_records"]:
    cols = [r[0] for r in q(f"SELECT column_name FROM information_schema.columns WHERE table_name='{t}'")]
    ok = "online_consultation_id" in cols
    print(f"   {t}.online_consultation_id: {'OK' if ok else 'MISSING'}")
    assert ok, f"{t} 缺 online_consultation_id"
checks = [r[0] for r in q("SELECT conname FROM pg_constraint WHERE conname IN ('ck_prescriptions_source','ck_consultation_records_source')")]
print(f"   XOR CHECK: {checks}")
assert len(checks) == 2, "缺票56 XOR CHECK 约束"
oc_cols = [r[0] for r in q("SELECT column_name FROM information_schema.columns WHERE table_name='online_consultations'")]
assert "diagnosis" not in oc_cols and "advice" not in oc_cols, "online_consultations 仍存 diagnosis/advice 列（票56 应已迁入接诊记录）"
print("   online_consultations 无 diagnosis/advice 列: OK")

print("== 3. extensions")
print("   ", [r[0] for r in q("SELECT extname FROM pg_extension ORDER BY extname")])

print("== 4. seed 关键表行数")
for t in ["hospitals", "hospital_campuses", "standard_departments", "department_categories",
          "departments", "doctors", "medications", "patients", "health_profiles",
          "health_profile_allergies", "knowledge_chunks", "knowledge_documents",
          "prescription_templates", "prescription_template_items"]:
    n = q(f'SELECT count(*) FROM "{t}"')[0][0]
    print(f"   {t}: {n}")

print("== 4b. staff_users 账号（B 端登录）：seed.sql 不种此表，由 StaffUserSeed 在 server-java 启动时补种")
print("   期望值按 .env 的 SEED_* 键存在性推导（镜像 StaffUserSeed.seedIfAbsent 逻辑）：")
print("   admin/doctor.lin/doctor.zhou 各需对应 SEED_ADMIN/DOCTOR/DOCTOR2_PASSWORD；13 位医生恒补种")
expected = 13
for key in ["SEED_ADMIN_PASSWORD", "SEED_DOCTOR_PASSWORD", "SEED_DOCTOR2_PASSWORD"]:
    if env.get(key):
        expected += 1
n = q("SELECT count(*) FROM staff_users")[0][0]
print(f"   staff_users: {n} (expected {expected})")
assert n == expected, (
    f"staff_users 应为 {expected}，实际 {n}。该表由 StaffUserSeed 在 server-java 启动时补种，"
    "seed.sql 不含其数据——重建（reset_zhiyu.py）后必须先重启 server-java 再跑本验证"
)

print("== 5. schedules 行数（15医生 × 7天 × 2时段 = 210）")
n = q("SELECT count(*) FROM schedules")[0][0]
print(f"   schedules: {n}")
assert n == 210, f"schedules 应为 210，实际 {n}"

print("== 5c. schedule_requests 表存在且为空（排班申请由医生提交产生，seed 不预置）")
q("""
    SELECT EXISTS (
        SELECT 1 FROM information_schema.tables WHERE table_name = 'schedule_requests'
    )""")
exists = q("""
    SELECT EXISTS (
        SELECT 1 FROM information_schema.tables WHERE table_name = 'schedule_requests'
    )""")[0][0]
assert exists, "schedule_requests 表不存在，schema.sql 未正确执行"
n = q("SELECT count(*) FROM schedule_requests")[0][0]
print(f"   schedule_requests: {n}")
assert n == 0, f"schedule_requests 应为 0，实际 {n}"

print("== 5b. 票61 seed 基线（林小满两条历史报告解读 + 16 条健康观测）")
for t, expected in [("report_interpretations", 2), ("health_observations", 16)]:
    n = q(f'SELECT count(*) FROM "{t}"')[0][0]
    print(f"   {t}: {n}")
    assert n == expected, f"{t} 应为 {expected}，实际 {n}"

print("== 5d. 票88 知识文档 seed 基线（1 条 SEED 文档 + 50 条 chunk 回填 document_id）")
n = q("SELECT count(*) FROM knowledge_documents")[0][0]
print(f"   knowledge_documents: {n}")
assert n == 1, f"knowledge_documents 应为 1（系统预置 SEED 文档），实际 {n}"
seed_doc = q("SELECT source, status, chunk_count FROM knowledge_documents WHERE id = 1")[0]
assert seed_doc[0] == "SEED", f"knowledge_documents(1) source 应为 SEED，实际 {seed_doc[0]}"
assert seed_doc[1] == "READY", f"knowledge_documents(1) status 应为 READY，实际 {seed_doc[1]}"
print(f"   SEED 文档: source={seed_doc[0]} status={seed_doc[1]} chunk_count={seed_doc[2]}")
orphan_chunks = q("SELECT count(*) FROM knowledge_chunks WHERE document_id IS NULL")[0][0]
print(f"   knowledge_chunks.document_id NULL 孤儿: {orphan_chunks}")
assert orphan_chunks == 0, f"knowledge_chunks 应全部回填 document_id，仍有 {orphan_chunks} 条为 NULL"
linked = q("SELECT count(*) FROM knowledge_chunks WHERE document_id = 1")[0][0]
print(f"   knowledge_chunks 指向 SEED 文档(document_id=1): {linked}")
assert linked == 50, f"knowledge_chunks 指向 SEED 文档应为 50，实际 {linked}"

print("== 6. 序列与 MAX(id) 对齐（setval 生效）")
for t, seq in [("hospitals", "hospitals_id_seq"), ("doctors", "doctors_id_seq"),
               ("patients", "patients_id_seq"), ("knowledge_chunks", "knowledge_chunks_id_seq"),
               ("knowledge_documents", "knowledge_documents_id_seq"),
               ("report_interpretations", "report_interpretations_id_seq"),
               ("health_observations", "health_observations_id_seq"),
               ("schedules", "schedules_id_seq")]:
    mx = q(f'SELECT MAX(id) FROM "{t}"')[0][0] or 0
    nxt = q(f"SELECT last_value FROM {seq}")[0][0]
    flag = "OK" if nxt == mx else f"OFF({nxt}!={mx})"
    print(f"   {t}: max_id={mx} seq_next={nxt} {flag}")

conn.close()
print("\n== 验证全部通过 ==")
