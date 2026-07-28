# 30 — 票 03 业务迁移：排班与号源池（Java）

**What to build:** 将票 03 已实现并验收的排班与号源池能力在 server-java 复刻并替换 Python 原件：排班 CRUD（日期 + 时段 + 号源总数）、号源池计数落 PG（total_slots/remaining_slots，ADR-0007）并同步初始化 Redis、扣减 = Redis 原子 DECR + PG 事务对账（硬规则 4，禁止先查后改）。

**Blocked by:** 29 — 票 02 业务迁移（医生/科室数据是排班前置）

**Status:** ready-for-agent

- [ ] schedules 表（schema.sql）含 total_slots / remaining_slots，CRUD API 可用
- [ ] 创建排班时同步初始化 Redis 号源计数，与票 03 行为一致（含边界场景）
- [ ] 扣减为 Redis 原子 DECR + PG 事务对账
- [ ] 并发扣减测试不超卖
- [ ] server-py 中对应 Python 业务代码已删除且无引用残留
