# 智愈（zhiyu-health）

AI 驱动的医疗 B+C 平台：C 端微信原生小程序（医疗 AI Agent）+ B 端 Vue3 管理后台 + FastAPI 单体后端。

## 运行拓扑

- 云服务器 `43.139.160.223`：仅运行 PostgreSQL 16 + pgvector、Redis 7、Neo4j 5。
- 本地开发机：运行 FastAPI、Vue3 B 端和微信开发者工具。
- 团队成员通过账号密码直连云端数据库；云安全组只允许团队固定公网 IP 访问数据库端口。

正式产品名为“智愈”，助手名为“小愈”；`zhiyu-health` 是仓库标识。

## 仓库结构

```text
server/        FastAPI 后端 + Agent（LangChain/LangGraph）
admin/         B 端 Vue3 + Element Plus
miniprogram/   C 端微信原生小程序 + Vant Weapp
deploy/        云端存储初始化脚本
docs/          ADR 与产品规格
```

## 1. 云服务器启动数据库

服务器需安装 Docker Engine 与 Docker Compose v2。在服务器仓库目录执行：

```bash
sh deploy/bootstrap-env.sh
docker compose up -d
docker compose ps
```

默认启动 PostgreSQL、Redis、Neo4j。测试用 Neo4j 按需启动：

```bash
docker compose --profile test up -d neo4j-test
```

云安全组放行 5432、6379、7474、7687（测试时再放行 7688），来源必须限定为团队固定公网 IP，禁止配置为 `0.0.0.0/0`。

## 2. 本地配置与 FastAPI

从 `.env.example` 创建 `.env`，填入服务器生成的 PostgreSQL、Redis、Neo4j 密码和后续功能所需的方舟配置。连接地址保持为 `43.139.160.223`，`.env` 永不入库或打印。

```bash
uv sync --frozen --dev
uv run uvicorn app.main:app --app-dir server --reload
```

验证三存储 health：

```bash
curl http://127.0.0.1:8000/api/health
```

期望 PostgreSQL、Redis、Neo4j 均返回 `ok`；PostgreSQL 检查同时确认 `vector` 扩展已启用。

## 3. 本地 B 端

```bash
cd admin
npm ci
npm run dev
```

访问 `http://localhost:5173`。Vite 将 `/api` 代理到本地 FastAPI `127.0.0.1:8000`。

## 4. 微信小程序

1. 在 `miniprogram/` 执行 `npm ci`。
2. 用微信开发者工具导入 `miniprogram/`，选择“工具 → 构建 npm”。
3. 模拟器关闭“校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书”。
4. health 页默认请求本机 `http://127.0.0.1:8000/api/health`。
5. 预览二维码与真机调试通过微信开发者工具的调试代理访问本地 FastAPI；不依赖已备案 HTTPS 域名。

提交的 `project.config.json` 使用游客 AppID，仅支持模拟器；预览或真机调试时在开发者工具中选择团队真实 AppID，该本地配置不提交。

## 测试与检查

```bash
uv run pytest
uv run ruff check server
uv run mypy server/app

cd admin
npm run typecheck
npm run build
```

Python 依赖由 `pyproject.toml` 声明、`uv.lock` 精确锁定；B 端和小程序依赖分别由各自的 `package-lock.json` 锁定。

## 文档

- 领域术语：`CONTEXT.md`
- 架构决策：`docs/adr/0001`–`0007`
- 产品规格：`docs/specs/0001-mvp.md`
- UI 约定：`docs/specs/0002-ui-conventions.md`
- 施工票单：`.scratch/zhiyu-mvp/issues/`
