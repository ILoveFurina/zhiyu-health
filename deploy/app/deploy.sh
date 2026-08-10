#!/bin/sh
# 云服务器应用层一键部署/更新：构建并启动 server-java + server-py + admin(nginx)。
# 在云服务器仓库根目录执行：  sh deploy/app/deploy.sh
# 前置：数据服务已由 compose.oneclick.yaml 在跑；deploy/app/env.cloud 已按 env.cloud.example 填好。
set -eu

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

if [ ! -f deploy/app/env.cloud ]; then
    echo "[失败] 缺少 deploy/app/env.cloud，请先复制 env.cloud.example 并填写真实值" >&2
    exit 1
fi

docker compose -f deploy/app/compose.app.yaml build
docker compose -f deploy/app/compose.app.yaml up -d

echo ""
echo "已启动，健康检查："
sleep 5
curl -sf http://127.0.0.1:8000/api/health && echo " <- server-py  ok" || echo "[告警] server-py :8000 未就绪，docker compose -f deploy/app/compose.app.yaml logs server-py"
curl -sf http://127.0.0.1:8080/api/health && echo " <- server-java ok" || echo "[告警] server-java :8080 未就绪，docker compose -f deploy/app/compose.app.yaml logs server-java"
# B 端静态页由 server-java 托管（无 nginx）：根路径应返回 admin 的 index.html
curl -sf http://127.0.0.1:8080/ | grep -qi '<!doctype html\|<html' && echo "admin 静态页 ok（server-java :8080 托管）" || echo "[告警] admin 静态页未命中，检查 ADMIN_STATIC_LOCATIONS 与镜像内 /app/admin-dist"
