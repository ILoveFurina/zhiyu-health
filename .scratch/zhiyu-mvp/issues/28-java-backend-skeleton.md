# 28 — server-java 骨架与 server-py 瘦身（ADR-0009 地基）

**What to build:** 按 ADR-0009 建立双栈骨架。新建 `server-java/`（Spring Boot + MyBatis-Plus 单体：controller/service/mapper/entity/rule/agentclient/config 分层；`schema.sql` + 幂等 seed 启动执行；PG/Redis 连通；健康检查接口；统一入口中间件：鉴权、限流、审计日志脱敏摘要）。打通对话链路骨架：server-java C 端对话入口鉴权后经 SSE 流式调 server-py，token 逐跳透传（可先用假流联调）。现有 `server/` 整体更名为 `server-py/` 并瘦身：删除业务 api/services/models/schemas（组织、排班、患者、会话持久化等），保留 agent/、tools/、知识检索与 Neo4j 访问，FastAPI 退化为仅暴露 Agent 编排接口的 HTTP 壳。

**Blocked by:** None — ADR-0009 地基票，可立即开工

**Status:** ready-for-agent

- [ ] server-java 工程可启动：健康检查接口、schema.sql + 幂等 seed、PG/Redis 连通
- [ ] server-java 统一入口中间件：鉴权、限流、审计日志（只落脱敏摘要）
- [ ] 对话链路骨架：server-java C 端对话接口 → SSE 流式调 server-py → token 逐跳透传
- [ ] server/ 更名 server-py/ 并完成瘦身：业务代码删除，agent/ 与知识检索保留可运行
- [ ] server-py 业务工具薄壳改造为 HTTP 回调 server-java（回调地址可配置）
- [ ] compose.yaml 增加 server-java 服务；两端联调冒烟通过
- [ ] server-java MockMvc 健康检查测试 + server-py TestClient Agent 接口测试
