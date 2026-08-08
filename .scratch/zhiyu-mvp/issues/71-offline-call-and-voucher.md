# 71 - 线下接诊叫号通知与挂号凭证

**What to build:** 线下挂号状态机补"叫号"步：`已约 → 就诊中 → 已接诊`（取消仍仅已约可发起，叫号后 C 端取消入口关闭）。医生在 B 端接诊台队列点"叫号"，挂号单推进到就诊中，同事务向 `in_app_messages` 写一条叫号通知（复用票 43 appointment_care 模式，`UNIQUE(related_appointment_id, type)` 幂等）；C 端消息页收到叫号通知卡片（诊室/序号/时段），"我的挂号"卡渲染就诊中状态，并新增挂号凭证视图（序号、医生、时段、院区地址）。挂号状态机与新消息类型随本票收编进 `contracts/`（现状态为 Java 硬编码，AGENTS.md 约定状态/消息类型从契约推导，miniprogram 手工镜像）。

**Blocked by:** 66 - 小程序视觉基线统一（C 端 pages/appointments 与消息页正是 T66 空态/骨架屏/微交互全量改动面，并行施工必撞 axml/acss）

**Status:** ready-for-agent

- [ ] contracts：挂号状态码/中文标签/叫号消息类型与文案进契约；Java、TS 从契约推导，miniprogram 手工镜像；`ContractsTest` 同步
- [ ] schema：`ck_appointments_status` 加新状态码；票完成后 `reset_zhiyu.py` 重建 + 重启 server-java + `verify_zhiyu.py`
- [ ] server-java：叫号接口（仅本人排班下的挂号单、仅已约可叫号、重复叫号幂等返回当前单）；叫号事务内写 `in_app_messages`；完成接诊允许已约/就诊中推进；非法迁移一律 409
- [ ] B 端：`ReceptionQueue` 操作列加"叫号"，状态 Tag 兼容就诊中；接诊抽屉在就诊中可完成接诊
- [ ] C 端：消息页叫号通知卡片；挂号卡就诊中状态渲染 + 取消按钮仅已约显示；挂号凭证视图
- [ ] 测试：状态机迁移与越权 service 单测、消息幂等、契约一致性；负向 HTTP 按需
- [ ] 票单置 done 前：README 依赖图 T71 节点加 `[x]`

## Comments

- 2026-08-08 立项：线下接诊现为"已约 → 已接诊"一跳，缺真实就医的叫号/候诊环节；用户拍板一票施工、被 T66 阻塞（C 端页面重叠），T66 合并后解除。同次讨论已先行落地接诊抽屉减负（无摘要不渲染摘要区块、处方区默认折叠，未建票）。
