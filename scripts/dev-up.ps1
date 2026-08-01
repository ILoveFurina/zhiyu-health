# 一键启动本地开发服务：server-py(:8000) / server-java(:8080) / admin(:5173)
# 小程序无法用脚本启动，需用支付宝小程序开发者工具手动导入 miniprogram/
# 用法：在仓库根目录执行  powershell -File scripts/dev-up.ps1
# 停止：到对应窗口按 Ctrl+C，或直接关掉窗口

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# ---- 前置检查 ----
if (-not (Test-Path "$root/.env")) {
    Write-Host "[失败] 缺少 .env，请先从 .env.example 复制并填写云数据库密码与 ARK key" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path "$root/admin/node_modules")) {
    Write-Host "[提示] admin/node_modules 不存在，先执行: npm --prefix admin ci" -ForegroundColor Yellow
}
if (-not (Test-Path "$root/.venv")) {
    Write-Host "[提示] .venv 不存在，先执行: uv sync --frozen --dev" -ForegroundColor Yellow
}

# ---- 每个服务开一个独立 PowerShell 窗口，日志直接可见 ----
$services = @(
    @{ Name = "server-py :8000";   Cmd = "uv run uvicorn app.main:app --app-dir server-py --reload" },
    @{ Name = "server-java :8080"; Cmd = "mvn -f server-java/pom.xml spring-boot:run" },
    @{ Name = "admin :5173";       Cmd = "npm --prefix admin run dev" }
)

foreach ($svc in $services) {
    Start-Process powershell -ArgumentList @(
        "-NoExit",
        "-Command",
        "Set-Location '$root'; `$host.ui.RawUI.WindowTitle = '$($svc.Name)'; $($svc.Cmd)"
    )
    Write-Host "[启动] $($svc.Name)（新窗口）"
}

# ---- 等待健康检查（admin 是前端 dev server，只查端口）----
function Wait-Url($url, $name, $timeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $null = Invoke-WebRequest -Uri $url -TimeoutSec 3 -UseBasicParsing
            Write-Host "[就绪] $name -> $url" -ForegroundColor Green
            return $true
        } catch {
            Start-Sleep -Seconds 3
        }
    }
    Write-Host "[超时] $name 未在 ${timeoutSec}s 内就绪，请到对应窗口看日志" -ForegroundColor Red
    return $false
}

$ok = $true
$ok = (Wait-Url "http://127.0.0.1:8000/api/health" "server-py") -and $ok
$ok = (Wait-Url "http://127.0.0.1:8080/api/health" "server-java") -and $ok

if ($ok) {
    Write-Host ""
    Write-Host "全部就绪：" -ForegroundColor Green
    Write-Host "  B 端管理后台  http://localhost:5173  (admin/admin123456)"
    Write-Host "  server-java   http://127.0.0.1:8080/api/health"
    Write-Host "  server-py     http://127.0.0.1:8000/api/health"
    Write-Host "  小程序请用支付宝开发者工具导入 miniprogram/"
} else {
    Write-Host "部分服务未就绪，详见上方提示与对应窗口日志" -ForegroundColor Red
    exit 1
}
