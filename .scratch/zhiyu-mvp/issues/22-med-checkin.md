# 22 — 服药打卡

**What to build:** 用药提醒从 Mock 升级为服药打卡：server-java 按已审核通过处方生成到点提醒（复用站内消息），患者点“已服用”后由 server-java 幂等写入打卡记录并计算连续天数；server-py 不参与业务写入。

**Blocked by:** 21 — 健康档案

**Status:** ready-for-agent

- [ ] server-java 按已审核通过处方的用法用量生成提醒记录，重复调度不得生成重复提醒
- [ ] 打卡接口校验患者与当前档案归属；同一提醒重复点击幂等，连续天数由 server-java service 计算
- [ ] 档案时间线可见打卡记录
- [ ] 状态、决定与站内消息类型从 `contracts/` 推导；DTO/Entity/View 映射使用 MapStruct，新 CRUD service 继承 `ServiceImpl`
- [ ] MockMvc 覆盖正常打卡、重复打卡、越权档案、未审核处方不生成提醒

## Comments

- 2026-07-29：补齐业务写入归属、幂等和跨栈边界，避免把提醒调度或打卡状态放入 server-py。
