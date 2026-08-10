# 云服务器应用层部署（server-java + server-py，无 nginx）

把应用从"本地运行"搬到云服务器，与既有数据服务（compose.oneclick.yaml 的
PG/Redis/Neo4j/MinIO）同机运行。**不用 nginx**：server-java 直接托管 admin 静态产物，
公网唯一入口是 server-java :8080。材料都在 `deploy/app/`：

| 文件 | 作用 |
| --- | --- |
| `deploy/app/compose.app.yaml` | 两应用编排，`network_mode: host` |
| `deploy/app/Dockerfile.bundle` | 一体镜像：admin 静态构建 + server-java 打包（构建上下文 = 仓库根） |
| `deploy/app/Dockerfile.server-py` | Agent 层镜像（构建上下文 = 仓库根） |
| `deploy/app/env.cloud.example` | 环境变量模板，复制为 `env.cloud` 填真实值（已 gitignore） |
| `deploy/app/deploy.sh` | 构建 + 启动 + 健康检查 |

## 拓扑

```
公网 ── :8080 server-java ──┬─ /api/**  业务接口 + 聊天 WS/SSE ── 127.0.0.1:8000 server-py ── 火山方舟
                            ├─ 其余路径  admin-dist 静态页（hash 路由，无回退需求）
                            └─ 127.0.0.1:5432/6379/7687/9000（compose.oneclick 数据服务）
```

关键前提（已核实）：

- server-java 的审计/鉴权/限流过滤器和 AdminInterceptor 全部只挂 `/api/*`，
  静态资源请求不经过任何鉴权逻辑；
- admin 是 hash 路由（`/#/...`），Spring 静态托管只需命中 `/` 的 index.html 与资源文件，
  无 history 回退需求；
- `spring.web.resources.static-locations` 由 `ADMIN_STATIC_LOCATIONS` 注入
  （`file:/app/admin-dist/` + 四个 classpath 默认值），本地开发不设该变量、行为不变；
- 应用容器用 host 网络直连宿主机端口上的数据服务，数据服务配置零改动。

## 首次部署

1. 服务器上拉取代码（git clone / pull 到任意目录）。
2. 准备环境变量：`cp deploy/app/env.cloud.example deploy/app/env.cloud`，按注释填值
   （数据库/MinIO 密码与云端 `compose.oneclick.yaml` 实际值一致，ARK key 同本地）。
3. 部署：`sh deploy/app/deploy.sh`（构建两镜像并启动，结尾自动健康检查：
   server-py / server-java / admin 静态页三项）。
4. 云安全组放行 **8080**（8000 不必对公网开放）。
5. 验证：`http://<服务器IP>:8080/` 打开 B 端（admin/admin123456），
   `http://<服务器IP>:8080/api/health` 返回 ok。

后续更新：`git pull && sh deploy/app/deploy.sh`。

## 小程序接入

- 开发者工具/预览：把 `miniprogram/utils/config.js` 的 `TUNNEL_API_BASE_URL` 改为
  `http://<服务器IP>:8080/api`（本地改动勿提交）。聊天 WS 为消息层首帧鉴权（票 82），
  端侧失败时自动降级 SSE。
- 真机正式发布：平台强制 HTTPS/WSS + request/socket 合法域名白名单（需已备案域名）。
  无 nginx 时 TLS 可配在 server-java 自身（`server.ssl.*` + 证书挂载），
  或届时再在前面补一层网关——评审 demo 阶段用开发者工具/预览即可，先不引入。

## 注意事项

- 数据服务仍由 `compose.oneclick.yaml` 人工管理，本部署不触碰；schema 重建照旧从本地跑
  `scripts/reset_zhiyu.py` + `verify_zhiyu.py`（重建后重启 server-java 容器补种 staff_users：
  `docker compose -f deploy/app/compose.app.yaml restart server-java`）。
- `DEMO_RESET_ENABLED` 在 `env.cloud` 中保持 `false`，公网环境不要开演示重置。
- MinIO 管理台 :9001、Neo4j browser :7474、server-py :8000 不要对公网放行（安全组只开 8080）。
- 镜像构建在服务器上进行，服务器只需 Docker，不需要 JDK/Maven/Node/Python。
- 若日后需要固定域名 + HTTPS + 多服务统一入口，再引入 nginx/caddy 做前置即可，
  当前两容器形态不做预留设计。
