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
} > .env

chmod 600 .env
echo "Created .env with generated local service secrets (mode 600)."
