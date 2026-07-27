# 03 — 排班与号源池

**What to build:** 管理员为医生创建排班（日期 + 时段 + 号源总数），号源以池计数落 PG（total_slots/remaining_slots，见 ADR-0007）并同步初始化 Redis 计数。

**Blocked by:** 02 — B 端认证与组织管理

**Status:** ready-for-agent

- [ ] schedules 表含 total_slots / remaining_slots
- [ ] 排班管理界面：列表 + 创建 + 停用
- [ ] 创建排班时同步初始化 Redis 号源计数
- [ ] API 测试覆盖创建与查询
