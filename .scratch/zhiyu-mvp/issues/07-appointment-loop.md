# 07 — 挂号闭环（P0 收口）

**What to build:** 完成挂号闭环：`create_appointment` 是只做参数校验的薄壳工具，调用 appointment service 完成 Redis 原子扣减 + PG 事务写挂号单（分配序号）；我的挂号列表与取消（取消返还号源）；挂号成功后 Agent 自动生成本次对话的病情摘要入库；挂号成功卡片提示"病情摘要已发送给医生"。

**Blocked by:** 05 — 医生推荐与结构化卡片

**Status:** done（分支 `codex/07-appointment-loop`，commit 见 git log）

- [x] `create_appointment` 薄壳工具只校验参数，Redis 扣减、PG 事务与补偿逻辑全部位于 appointment service
- [x] Redis 原子 DECR + PG 事务写入；PG 事务失败时补偿回补 Redis
- [x] 挂号单添加患者 + 排班唯一约束，同一患者对同一排班重复请求返回已有挂号结果且不重复扣减
- [x] 并发测试：N 并发抢最后 1 号源，恰好 1 个成功，Redis 与 PG 计数一致
- [x] 我的挂号列表、取消挂号并返还号源；仅“已约 → 已取消”首次状态转换执行 Redis/PG 回补，重复取消不得重复返还
- [x] 挂号成功触发病情摘要生成并入库
- [x] 挂号成功卡片含"摘要已发送医生"提示
- [x] get_appointment 接入 Agent 工具（患者可直接问 Agent 查自己的挂号）

实施备注：

- server-java 的 `AppointmentService` 在排班行锁保护下完成幂等检查、Redis 原子预扣、PG 余量对账、序号分配与挂号单写入；提交失败精确回补 Redis。取消通过挂号单行锁保证只有首次状态转换回补两端号源。
- Agent 在 `create_appointment` 工具中先完成挂号，再自动保存模型基于本次会话生成的病情摘要；摘要失败不撤销已成功挂号，仍返回挂号成功卡片。患者 ID 与会话 ID 通过 LangChain `ToolRuntime` 隐藏注入，不暴露给模型。
- Java→Python 与 Python→Java 的患者级链路均使用无默认值的 `AGENT_CALLBACK_SECRET` 服务间认证；C 端提供对话成功卡片、“我的挂号”列表和取消入口，所有摘要界面均显示免责声明标注。
- 验证：server-java 94 项、server-py 21 项通过，ruff 与 mypy 全绿；C 端 12 个 JS 文件通过语法检查，新增/修改 JSON 可解析。支付宝开发者工具普通编译成功，AI 首页、“我的挂号”空状态与返回链路实测无红色控制台错误；仅有本地 HTTP 非 HTTPS 的开发警告。
- 双轴 code-review 初审发现工具回调认证、卡片点击意图与摘要触发顺序问题，均已修正；测试文件行数按用户明确指示不拆分。
