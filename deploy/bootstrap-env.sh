#!/bin/sh
set -eu

if [ -e .env ]; then
    echo ".env already exists; refusing to overwrite it" >&2
    exit 1
fi

umask 077
{
    printf 'POSTGRES_PASSWORD='
    openssl rand -hex 24
    printf 'REDIS_PASSWORD='
    openssl rand -hex 24
    printf 'NEO4J_PASSWORD='
    openssl rand -hex 24
    printf 'NEO4J_TEST_PASSWORD='
    openssl rand -hex 24
    # MinIO 拍照分析原图持久化（ADR-0023）：compose 以 MINIO_ROOT_USER/PASSWORD 注入容器。
    # access key 至少 3 位、secret key 至少 8 位，openssl 十六进制串均满足。
    printf 'MINIO_ACCESS_KEY='
    openssl rand -hex 12
    printf 'MINIO_SECRET_KEY='
    openssl rand -hex 24
    # 桶名与 server-java zhiyu.minio.bucket 默认值一致；Java 首次写入会自动建桶，此处仅登记。
    printf 'MINIO_BUCKET=zhiyu-photos\n'
} > .env

chmod 600 .env
echo "Created .env with generated local service secrets (mode 600)."
