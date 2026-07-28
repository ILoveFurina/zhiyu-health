# 05 — 医生推荐与结构化卡片

**What to build:** Agent 工具链首批业务工具上线：`recommend_doctors`（按科室查有号医生）、`get_doctor_slots`（查排班剩余号源）；对话中输出结构化医生推荐卡片（照片/职称/擅长/剩余号源），用户可在卡片上选医生、选时段。情感化人设 system prompt 第一版随此票落地。

**Blocked by:** 30 — 票 03 业务迁移（Java）；31 — 票 04 拆分迁移

**Status:** done（分支 `codex/issue-05-doctor-recommend`，commit 见 git log）

- [x] 两个工具经 service 层查 PG，只返回有剩余号源的医生/时段
- [x] 小程序自定义卡片消息：渲染、点选、回传选择
- [x] 情感化人设 system prompt（关怀语气基调）
- [x] fake LLM 断言工具调用序列的测试

实施备注：

- server-java 新增只读 Agent 工具回调：按完整科室名称聚合启用且今天以后仍有号源的医生，按医生查询可预约排班；server-py 的 `recommend_doctors` / `get_doctor_slots` 仅作 HTTP 薄壳，不直连业务库。
- LangGraph 从 ToolMessage 投影 `doctor_recommendations` / `doctor_slots` SSE 事件；卡片与普通 AI 文本一样由 server-py 注入、server-java 出口兜底免责声明，并以结构化 JSON 消息持久化。卡片 JSON 只供历史渲染，不重复进入 LLM 自然语言上下文。
- 小程序 `doctor-card` 独立组件展示照片、职称、擅长与剩余号源；选择医生回传 `doctor_id` 触发时段查询，选择时段回传 `schedule_id`，挂号扣减留给票 07。
- 依赖实现已对照 `uv.lock`（langchain 1.3.14、langchain-core 1.5.1、langgraph 1.2.9）及 LangChain 官方工具/流式文档，沿既有 `astream(..., stream_mode="messages")` 读取 ToolMessage。
- 验证：server-java 全套 81 项、server-py 全套 17 项通过，ruff 与 mypy 全绿；独立 server-java 实例真实连接 PostgreSQL 执行两条新查询成功（当前库无未来号源，返回空数组）。支付宝小程序开发者工具 3.10.15 编译成功，AI 页无控制台错误；运行时注入两类 SSE payload，卡片渲染、免责声明、医生/时段点击回传均实测通过。仅有本地 HTTP 非 HTTPS 的开发工具既有警告。
- 双轴 code-review：Spec 初审指出卡片未持久化，已补写 messages 并排除出 LLM 文本上下文；Standards 指出的次要文字色值已按 Spec 0002 改为 `#999`。跨三运行时的事件名同步属于协议必要映射，未引入代码生成机制。
