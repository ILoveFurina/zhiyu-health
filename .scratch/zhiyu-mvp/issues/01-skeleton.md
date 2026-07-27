# 01 — 项目骨架与基础设施

**What to build:** 三端（微信小程序 / B 端 Vue3 / FastAPI 后端）脚手架全部跑通；docker-compose 一键起 PostgreSQL 16（含 pgvector）、Redis 7、Neo4j 5；一个打穿三端与三个存储的 health 接口证明全链路可用。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] `docker compose up` 在云服务器一键起全部依赖（PG 启用 pgvector 扩展、Redis、Neo4j），服务绑 127.0.0.1，端口不对公网开放
- [ ] 团队成员经 SSH 隧道或 Tailscale 访问云端依赖（文档写明命令）
- [ ] 后端依赖由 `pyproject.toml` 声明并生成 `uv.lock`，两者均提交入库；安装与测试使用锁文件复现
- [ ] FastAPI 骨架含 health 接口，分别连通 PG / Redis / Neo4j 并返回各自状态
- [ ] Vue3 + Element Plus 脚手架能调用 health 接口并展示结果
- [ ] 微信原生小程序脚手架（Vant Weapp 接入）能调用 health 接口
- [ ] 小程序演示以开发者工具模拟器为主；预览二维码 + 开发者工具真机调试经工具代理可调用本地 health 接口，不依赖 HTTPS 域名
- [ ] README 写明一键启动步骤
