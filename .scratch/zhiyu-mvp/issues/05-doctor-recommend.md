# 05 — 医生推荐与结构化卡片

**What to build:** Agent 工具链首批业务工具上线：`recommend_doctors`（按科室查有号医生）、`get_doctor_slots`（查排班剩余号源）；对话中输出结构化医生推荐卡片（照片/职称/擅长/剩余号源），用户可在卡片上选医生、选时段。情感化人设 system prompt 第一版随此票落地。

**Blocked by:** 30 — 票 03 业务迁移（Java）；31 — 票 04 拆分迁移

**Status:** ready-for-agent

- [ ] 两个工具经 service 层查 PG，只返回有剩余号源的医生/时段
- [ ] 小程序自定义卡片消息：渲染、点选、回传选择
- [ ] 情感化人设 system prompt（关怀语气基调）
- [ ] fake LLM 断言工具调用序列的测试
