# 31 — 票 04 拆分迁移：对话主干双栈化

**What to build:** 按 ADR-0009 将票 04 对话主干拆为双栈形态。迁往 server-java：会话/消息持久化（PG 写入，惰性创建与标题规则不变）、C 端对话 HTTP/SSE 入口、红线症状规则引擎（rule/，先于一切 LLM 调用执行，命中即中断并返回红色警告事件；红线警告为规则引擎产物，不注入免责声明——与票 04 实施备注口径一致）、patients 表与 C 端 mock 登录（令牌带 scope 防 B/C 端混用）。留在 server-py：LangGraph 循环、免责声明注入、推理档位映射（导诊 low、解读 high，永不直传 `auto`），对外仅暴露 Agent 编排接口。开场推荐提示词与三档切换行为不变。

**Blocked by:** 28 — server-java 骨架与 server-py 瘦身

**Status:** ready-for-agent

- [ ] 会话/消息表（schema.sql）与持久化 API 在 server-java
- [ ] C 端对话入口在 server-java：鉴权 → 红线规则前置 → SSE 调 server-py → 逐跳透传
- [ ] 红线命中即中断、返回红色警告事件、全程不调 server-py；规则测试覆盖必触发/不误触/命中不调 Agent
- [ ] patients 表 + C 端 mock 登录在 server-java，令牌带 scope
- [ ] 免责声明由 server-py 注入、server-java 出口兜底校验
- [ ] 小程序端无感切换：SSE 契约不变，my.request 整读回放逻辑不变
- [ ] server-py 对 server-java 的业务回调用 fake HTTP 替身测试；LLM fake 断言工具调用序列
