# 智愈先锋（[zhiyu-health]()）

AI 驱动的全链路医疗健康平台：C 端微信原生小程序（医疗 AI Agent）+ B 端医生/医院管理后台（Vue3）+ 全 Python（FastAPI）单体后端。两周 demo 项目。

## 仓库结构

```
server/        # FastAPI 后端 + Agent（LangChain/LangGraph）
admin/         # B 端 Vue3 + Element Plus
miniprogram/   # C 端微信原生小程序 + Vant Weapp
docs/          # ADR（adr/）与规格（specs/）
CONTEXT.md     # 领域术语表
```

## 开发环境

开发环境的数据存储（PostgreSQL+pgvector / Redis / Neo4j）以 docker-compose 部署在云服务器 `43.139.160.223`，端口不对公网开放；本地经 SSH 隧道或 Tailscale 访问（见 `.env.example`）。后端本地 `uvicorn --reload` 运行，`admin` 用 Vite dev server；小程序演示以微信开发者工具模拟器为主，真机使用“预览二维码 + 开发者工具真机调试”并经工具代理访问本地后端，不要求 HTTPS 域名。密钥经 `.env`（私聊分发，永不入库）。

## 文档

- 领域术语：`CONTEXT.md`
- 架构决策：`docs/adr/0001`–`0007`
- 产品规格：`docs/specs/0001-mvp.md`
- 施工票单：`.scratch/zhiyu-mvp/issues/01`–`26`（**票号不代表施工顺序**，以各票 Blocked by 为准——存在小编号依赖大编号的正常情况，如 11←21、16←21）
