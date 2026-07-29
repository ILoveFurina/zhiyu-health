# SlotAccounting：号源补偿收敛为命令式句柄

Status: accepted

号源一致性 = Redis 原子计数 + PostgreSQL 事务对账（ADR-0007），Redis 不参与 PG 事务，因此每个"Redis 操作 + PG 写入"序列都必须带失败补偿。事故背景：这段补偿逻辑一度存在 4+1 份变体——挂号扣减、取消退还、容量调整、排班初始化 4 处业务点各自手写 try-catch，外加 1 份复制后改漏的；变体间补偿粒度不一（判负回补与提交失败回补混用、全量 INCR 覆盖并发扣减），超卖与计数漂移的风险点正是变体间的差异。

决策：`service/SlotAccounting.java` 是唯一操作 `SlotCounter` 的组件（ArchUnit 强制，测试代码除外），对外只暴露命令式句柄：

- `withDeduction` / `Deduction.acquire()`：预扣；
- `withRefund` / `Refund.grant()`：退还；
- `withAdjustment` / `Adjustment.apply(delta)`：容量增量调整；
- `withInitialization` / `Initialization.init()`：计数初始化；
- 另有 `tryDeduct` 承载非事务型单次扣减。

每个句柄在事务体内记录"本次已成功应用的 Redis 变更"（acquired / appliedDelta / initializedScheduleId 等状态），事务体抛出（含 PG 提交失败）时只反向补偿已成功部分，不多补不漏补；PG 写入以 lambda 传入、在调用方事务内运行，Redis 操作时序由句柄固定。

被否决的方案：

- 单 try-catch 分散在各调用点：即事故前形态。4+1 份变体证明复制必然 drift，补偿条件（判负才回补、已成功才回补）靠注释约定无法统一。
- AOP / 声明式增强（注解触发补偿切面）：补偿语义藏进切面，与 PG 事务边界（尤其提交失败）的耦合不可见；切面拿不到"本次实际成功的 Redis 变更"这一状态，只能做粗放补偿；demo 规模引入 AOP 的心智成本高于收益。

命令式句柄的本质是把补偿状态显式化、类型化：调用点写法唯一，补偿分支由句柄实现唯一承载，配合 ArchUnit 的 SlotCounter 访问收口，新增号源业务没有第二条路可走。
