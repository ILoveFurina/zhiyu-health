# 07 — 挂号闭环（P0 收口）

**What to build:** 完成挂号闭环：`create_appointment` 是只做参数校验的薄壳工具，调用 appointment service 完成 Redis 原子扣减 + PG 事务写挂号单（分配序号）；我的挂号列表与取消（取消返还号源）；挂号成功后 Agent 自动生成本次对话的病情摘要入库；挂号成功卡片提示"病情摘要已发送给医生"。

**Blocked by:** 05 — 医生推荐与结构化卡片

**Status:** ready-for-agent

- [ ] `create_appointment` 薄壳工具只校验参数，Redis 扣减、PG 事务与补偿逻辑全部位于 appointment service
- [ ] Redis 原子 DECR + PG 事务写入；PG 事务失败时补偿回补 Redis
- [ ] 挂号单添加患者 + 排班唯一约束，同一患者对同一排班重复请求返回已有挂号结果且不重复扣减
- [ ] 并发测试：N 并发抢最后 1 号源，恰好 1 个成功，Redis 与 PG 计数一致
- [ ] 我的挂号列表、取消挂号并返还号源；仅“已约 → 已取消”首次状态转换执行 Redis/PG 回补，重复取消不得重复返还
- [ ] 挂号成功触发病情摘要生成并入库
- [ ] 挂号成功卡片含"摘要已发送医生"提示
- [ ] get_appointment 接入 Agent 工具（患者可直接问 Agent 查自己的挂号）
