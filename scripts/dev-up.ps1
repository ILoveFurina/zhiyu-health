# 一键启动本地开发服务：server-py(:8000) / server-java(:8080) / admin(:5173)
# 小程序无法用脚本启动，需用支付宝小程序开发者工具手动导入 miniprogram/
# 用法：在仓库根目录执行  powershell -File scripts/dev-up.ps1
# 停止：到对应窗口按 Ctrl+C，或直接关掉窗口
# 端口被占用时自动释放：本项目进程（python/java/node）直接强杀，其他进程询问确认后再强杀

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
# server-py 必须走 scripts/run-server-py.py（Windows 上 psycopg 异步要求 SelectorEventLoop，
# 直接 uvicorn 命令会因 ProactorEventLoop 崩溃）。
# admin 用 PORT=5173 固定 dev 端口（umi 默认 8000，若 server-py 未起会抢占 8000，
# 导致 server-java 的 Agent 调用打到 admin 上 404，见小程序对话失败问题）。
$services = @(
    @{ Name = "server-py :8000";   Port = 8000; Cmd = "uv run python scripts/run-server-py.py" },
    @{ Name = "server-java :8080"; Port = 8080; Cmd = "mvn -f server-java/pom.xml spring-boot:run" },
    @{ Name = "admin :5173";       Port = 5173; Cmd = "`$env:PORT='5173'; npm --prefix admin run dev" }
)

# 端口被占用时自动释放（强占）：白名单进程（本项目的 python/java/node 运行时）直接强杀，
# 其他进程打印信息询问 y/N；杀完轮询等待端口真正空出（进程退出/TIME_WAIT 延迟），再放行启动。
function Free-Port($port, $svcName) {
    $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if (-not $listeners) { return }

    foreach ($conn in $listeners) {
        $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        if (-not $proc) { continue }
        $auto = $proc.ProcessName -in @("python", "java", "node")
        if (-not $auto) {
            $answer = Read-Host "端口 $port 被进程 $($proc.ProcessName) (PID $($proc.Id)) 占用，非本项目进程，强杀? [y/N]"
            if ($answer -notmatch '^[yY]') {
                Write-Host "[失败] 用户拒绝释放端口 $port，$svcName 无法启动" -ForegroundColor Red
                exit 1
            }
        }
        try {
            Stop-Process -Id $proc.Id -Force -ErrorAction Stop
            Write-Host "[释放] 端口 $port 被 $($proc.ProcessName) (PID $($proc.Id)) 占用，已强杀" -ForegroundColor Yellow
        } catch {
            Write-Host "[失败] 无法结束进程 $($proc.ProcessName) (PID $($proc.Id)): $($_.Exception.Message)" -ForegroundColor Red
            exit 1
        }
    }

    # 等待端口真正空出，避免进程刚退出/TIME_WAIT 时服务启动绑定失败
    $deadline = (Get-Date).AddSeconds(10)
    while (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        if ((Get-Date) -gt $deadline) {
            Write-Host "[失败] 端口 $port 在 10s 内未释放" -ForegroundColor Red
            exit 1
        }
        Start-Sleep -Milliseconds 500
    }
}

foreach ($svc in $services) {
    Free-Port $svc.Port $svc.Name
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
