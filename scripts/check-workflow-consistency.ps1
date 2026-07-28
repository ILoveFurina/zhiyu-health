$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$failures = [System.Collections.Generic.List[string]]::new()

function Require-File([string]$relativePath) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $relativePath))) {
        $failures.Add("缺少文件：$relativePath")
    }
}

function Require-Pattern([string]$relativePath, [string]$pattern, [string]$message) {
    $path = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $path)) {
        $failures.Add("缺少文件：$relativePath")
        return
    }
    $content = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    if ($content -notmatch $pattern) {
        $failures.Add($message)
    }
}

Require-File '.scratch/zhiyu-mvp/spec.md'
if (Test-Path -LiteralPath (Join-Path $repoRoot 'docs/specs/0001-mvp.md')) {
    $failures.Add('旧 Spec 路径仍存在：docs/specs/0001-mvp.md')
}

Require-Pattern 'AGENTS.md' '\.scratch/zhiyu-mvp/spec\.md' 'AGENTS.md 未指向 canonical Spec'
Require-Pattern 'AGENTS.md' '禁止通过 SSH 登录云服务器' 'AGENTS.md 缺少禁止 Agent 远程操作的硬约束'
Require-Pattern 'AGENTS.md' '全部测试均在本地运行' 'AGENTS.md 未明确测试在本地运行'
Require-Pattern '.env.example' '(?m)^DATABASE_JDBC_URL=' '.env.example 缺少 server-java 的 DATABASE_JDBC_URL'
Require-Pattern '.env.example' '(?m)^REDIS_HOST=' '.env.example 缺少 server-java 的 REDIS_HOST'
Require-Pattern '.scratch/zhiyu-mvp/issues/02-org-admin.md' '\*\*Status:\*\* retired' '票 02 未标记为 retired'
Require-Pattern '.scratch/zhiyu-mvp/issues/03-schedule-slots.md' '\*\*Status:\*\* retired' '票 03 未标记为 retired'
Require-Pattern '.scratch/zhiyu-mvp/spec.md' 'server-java 主 seam' 'Spec 未记录 server-java 测试 seam'
Require-Pattern '.scratch/zhiyu-mvp/spec.md' 'server-py 主 seam' 'Spec 未记录 server-py 测试 seam'

$compose = Get-Content -LiteralPath (Join-Path $repoRoot 'compose.yaml') -Raw -Encoding UTF8
if ($compose -match '(?m)^  server-java:\s*$') {
    $failures.Add('云数据库 Compose 不应包含 server-java 应用服务')
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output 'Workflow and execution-topology consistency checks passed.'
