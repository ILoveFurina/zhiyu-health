# 30 — 票 03 业务迁移：排班与号源池（Java）

**What to build:** 将票 03 已实现并验收的排班与号源池能力在 server-java 复刻并替换 Python 原件：排班 CRUD（日期 + 时段 + 号源总数）、号源池计数落 PG（total_slots/remaining_slots，ADR-0007）并同步初始化 Redis、扣减 = Redis 原子 DECR + PG 事务对账（硬规则 4，禁止先查后改）。

**Blocked by:** 29 — 票 02 业务迁移（医生/科室数据是排班前置）

**Status:** done（codex/issue-30，commit 见 git log）

- [x] schedules 表（schema.sql）含 total_slots / remaining_slots，CRUD API 可用
- [x] 创建排班时同步初始化 Redis 号源计数，与票 03 行为一致（含边界场景）
- [x] 扣减为 Redis 原子 DECR + PG 事务对账
- [x] 并发扣减测试不超卖
- [x] server-py 中对应 Python 业务代码已删除且无引用残留

实施备注：
- 管理接口保留票 03 的列表、创建、停用契约，并补齐按 ID 查询、更新与 DELETE（停用语义）；仅 admin 可操作，字段保持 snake_case，时段限定上午/下午/晚上。
- 创建排班在 PG 事务内写表并初始化 `schedule:{id}:remaining_slots`；提交失败会清理 Redis 孤儿计数。停用只更新 `is_active`，不删除或覆盖号源池。
- 扣减先执行 Redis 原子 DECR，再用 PG 条件 UPDATE 对账；售罄、PG 更新失败或事务失败均返还 Redis 预扣。容量更新以 `SELECT ... FOR UPDATE` 锁行，在事务内计算实际 delta，PG 条件增量更新与 Redis INCRBY 同步，避免更新与扣减或并发更新互相覆盖。
- TDD 覆盖管理 HTTP seam、创建边界、事务补偿、10 并发抢最后 1 个号源、容量更新与扣减交错、并发容量更新及停用不回写陈旧计数；server-py 无 Python 排班业务实现引用残留。
- code-review 双轴结论：初审各发现 1 项容量更新竞态，修复并两轮复核后 Standards 0 项、Spec 0 项。
