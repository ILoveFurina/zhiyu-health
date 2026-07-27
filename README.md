# 智愈（zhiyu-health）

AI 驱动的医疗 B+C 平台：C 端微信原生小程序（医疗 AI Agent）+ B 端 Vue3 管理后台 + FastAPI 单体后端。

## 仓库结构

```text
server/        FastAPI 后端 + Agent（LangChain/LangGraph）
admin/         B 端 Vue3 + Element Plus
miniprogram/   C 端微信原生小程序 + Vant Weapp
deploy/        存储初始化脚本
docs/          ADR 与产品规格
```

## 云端拓扑

整套运行环境统一部署在 `43.139.160.223`：

- `gateway`：唯一公网入口，Nginx 在 80 端口提供 B 端页面并反向代理 `/api`。
- `api`：FastAPI 只在 Compose 内部网络提供服务。
- PostgreSQL 16 + pgvector、Redis 7、Neo4j 5：宿主机端口只绑定 `127.0.0.1`，不对公网开放。
- `neo4j-test` 与 `api-tests`：仅在 `test` profile 中启动，不复用演示 Neo4j 数据。

正式产品名为“智愈”，助手名为“小愈”；`zhiyu-health` 是仓库标识。

## 一键部署

服务器需预先安装 Docker Engine 与 Docker Compose v2。登录服务器并进入仓库后：

```bash
cp .env.example .env
# 编辑 .env：替换全部“改密码/改密钥”占位值；数据库密码使用长随机字母数字串，避免 URL 特殊字符转义问题
docker compose up -d --build
docker compose ps
curl http://127.0.0.1/api/health
```

期望 health 响应：

```json
{
  "status": "ok",
  "services": {
    "postgres": { "status": "ok" },
    "redis": { "status": "ok" },
    "neo4j": { "status": "ok" }
  }
}
```

浏览器访问 `http://43.139.160.223` 查看 B 端 health 页面。云安全组只需为当前演示开放 80；不要开放 5432、6379、7474、7687 或 7688。

常用运维命令：

```bash
docker compose logs -f api gateway
docker compose pull
docker compose up -d --build
docker compose down
```

`docker compose down` 不删除数据卷。不要在有演示数据时执行带 `-v` 的命令。

## 锁文件与测试

Python 依赖由 `pyproject.toml` 声明、`uv.lock` 精确锁定；B 端和小程序依赖分别由各自的 `package-lock.json` 锁定。云端以容器复现测试环境：

```bash
docker compose --profile test run --rm api-tests
```

开发机只做代码检查时可执行：

```bash
uv sync --frozen --dev
uv run pytest
uv run ruff check server
uv run mypy server/app

cd admin
npm ci
npm run typecheck
npm run build
```

## SSH 隧道与 Tailscale

数据库端口保持回环绑定。需要用本地数据库工具访问云端依赖时，通过 SSH 隧道转发：

```bash
ssh -N \
  -L 5432:127.0.0.1:5432 \
  -L 6379:127.0.0.1:6379 \
  -L 7474:127.0.0.1:7474 \
  -L 7687:127.0.0.1:7687 \
  <服务器用户>@43.139.160.223
```

如果团队已接入同一 Tailnet，可将 SSH 目标替换为服务器的 Tailscale IP 或 MagicDNS 名称；端口仍通过隧道访问，不修改 Compose 的 `127.0.0.1` 绑定。

## 微信小程序

1. 在 `miniprogram/` 执行 `npm ci`。
2. 用微信开发者工具导入 `miniprogram/`，选择“工具 → 构建 npm”。
3. 模拟器开发时关闭“校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书”，health 页会请求 `http://43.139.160.223/api/health`。
4. 预览二维码/真机调试需要开发者工具调试代理；若脱离开发者工具运行，则必须为云端入口配置 HTTPS 与小程序 request 合法域名。

小程序不上架、不审核；演示以开发者工具模拟器为主。

## 文档

- 领域术语：`CONTEXT.md`
- 架构决策：`docs/adr/0001`–`0007`
- 产品规格：`docs/specs/0001-mvp.md`
- UI 约定：`docs/specs/0002-ui-conventions.md`
- 施工票单：`.scratch/zhiyu-mvp/issues/`
