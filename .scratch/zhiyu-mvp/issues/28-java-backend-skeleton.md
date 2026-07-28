# 28 — server-java 骨架与 server-py 瘦身（ADR-0009 地基）

**What to build:** 按 ADR-0009 建立双栈骨架。新建 `server-java/`（Spring Boot + MyBatis-Plus 单体：controller/service/mapper/entity/rule/agentclient/config 分层；`schema.sql` + 幂等 seed 启动执行；PG/Redis 连通；健康检查接口；统一入口中间件：鉴权、限流、审计日志脱敏摘要）。打通对话链路骨架：server-java C 端对话入口鉴权后经 SSE 流式调 server-py，token 逐跳透传（可先用假流联调）。现有 `server/` 整体更名为 `server-py/` 并瘦身：删除业务 api/services/models/schemas（组织、排班、患者、会话持久化等），保留 agent/、tools/、知识检索与 Neo4j 访问，FastAPI 退化为仅暴露 Agent 编排接口的 HTTP 壳。

**Blocked by:** None — ADR-0009 地基票，可立即开工

**Status:** done（分支 28-java-backend-skeleton，commit 26ae1bf）

- [x] server-java 工程可启动：健康检查接口、schema.sql + 幂等 seed、PG/Redis 连通
- [x] server-java 统一入口中间件：鉴权、限流、审计日志（只落脱敏摘要）
- [x] 对话链路骨架：server-java C 端对话接口 → SSE 流式调 server-py → token 逐跳透传
- [x] server/ 更名 server-py/ 并完成瘦身：业务代码删除，agent/ 与知识检索保留可运行
- [x] server-py 业务工具薄壳改造为 HTTP 回调 server-java（回调地址可配置）
- [x] compose.yaml 增加 server-java 服务；两端联调冒烟通过
- [x] server-java MockMvc 健康检查测试 + server-py TestClient Agent 接口测试

实施备注：
- 现状与票面出入：原 server/ 并无 tools/ 与知识检索代码，业务工具薄壳为新建（`tools/business.py`，`SERVER_JAVA_BASE_URL` 可配，lifespan 装配）；红线引擎按票 31 迁 server-java，本票已从 server-py 删除。
- 联调冒烟在本机以假流完成（.env 无方舟密钥；remote PG/Redis/Neo4j 经 .env 连通）：健康双端 ok、无令牌 401、SSE 逐跳透传且 event 行先于 data 行（小程序 `utils/chat-stream.js` 按行序解析，已有 MockMvc 测试锁定）。compose 启动未实测（本机无 Docker）。
- 限流在鉴权之后执行（order 20 鉴权 / 30 限流），按 subject 计键；审计最外层 order 10。
- seed.sql 末尾 setval 对齐 identity 序列（显式 id 不推进序列，防后续写入撞主键）。
- 遗留判断项：token 流式窗口期界面无免责声明（仅 message 事件携带），端侧展示策略待票 31/小程序票确认。
