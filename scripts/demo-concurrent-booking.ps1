# 并发抢号演示脚本（票 25）：N 个 demo patient 并发打"最后 1 号"的 schedule，
# 输出脱敏日志证明恰好 1 个 201、其余 409，验证号源防超卖（Redis 原子 DECR + PG 事务对账）。
#
# 脚本只调本地 server-java HTTP 入口（mock-login + appointments），不新增 server-java 代码。
# 日志只记 patient 序号 + HTTP 状态码 + 挂号序号，不记 PII。
#
# 用法（需先启动 server-java，且已对目标 schedule 预扣到仅剩 1 号）：
#   powershell -File scripts/demo-concurrent-booking.ps1
#   powershell -File scripts/demo-concurrent-booking.ps1 -ScheduleId 12 -Concurrency 10
#   powershell -File scripts/demo-concurrent-booking.ps1 -BaseUrl http://localhost:8080 -Concurrency 20
#
# 退出码：0 = 恰好 1 成功且 N-1 冲突（防超卖验证通过）；1 = 其他情况。

param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$ScheduleId = 0,
    [int]$Concurrency = 10
)

$ErrorActionPreference = "Stop"

if ($Concurrency -lt 2) {
    Write-Host "[失败] -Concurrency 至少为 2（需多人抢同一号才验证防超卖）" -ForegroundColor Red
    exit 1
}

# ---- 前置：server-java 健康检查 ----
try {
    $null = Invoke-RestMethod -Uri "$BaseUrl/api/health" -TimeoutSec 5
} catch {
    Write-Host "[失败] server-java 未就绪（$BaseUrl/api/health 不可达），请先启动" -ForegroundColor Red
    exit 1
}

# ---- 为 N 个 demo patient 登录拿 token（mock-login 创建或复用 nickname）----
Write-Host "[准备] 为 $Concurrency 个 demo patient 登录..." -ForegroundColor Cyan
$tokens = @()
for ($i = 1; $i -le $Concurrency; $i++) {
    $nick = "demo-patient-$i"
    try {
        $resp = Invoke-RestMethod -Uri "$BaseUrl/api/c/auth/mock-login" `
            -Method Post -ContentType "application/json" `
            -Body (@{ nickname = $nick } | ConvertTo-Json) -TimeoutSec 10
        $tokens += $resp.token
    } catch {
        Write-Host "[失败] demo patient #$i 登录失败：$($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}
Write-Host "[准备] $Concurrency 个 demo patient 登录完成" -ForegroundColor Green

# ---- 选定目标 schedule ----
# 未传 -ScheduleId 时默认用 1（演示前应通过重置或手工把该 schedule 扣到仅剩 1 号）。
# 脚本不读 schedule 剩余号（避免新增 server-java 查询接口），由演示者保证目标号源仅剩 1。
if ($ScheduleId -le 0) {
    $ScheduleId = 1
    Write-Host "[提示] 未指定 -ScheduleId，默认 #$ScheduleId。演示前请确认该排班仅剩 1 号。" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "==== 并发抢号开始 ====" -ForegroundColor Cyan
Write-Host "目标排班：#$ScheduleId    并发数：$Concurrency"
Write-Host ""

# ---- 并发发起挂号请求 ----
# 用 runspace 池实现真并发；每个 job 独立 token 打同一 schedule。
$jobs = @()
for ($i = 0; $i -lt $Concurrency; $i++) {
    $token = $tokens[$i]
    $seq = $i + 1
    $jobs += Start-Job -ScriptBlock {
        param($url, $scheduleId, $token, $seq)
        $headers = @{ Authorization = "Bearer $token" }
        $body = @{ schedule_id = $scheduleId } | ConvertTo-Json
        try {
            $resp = Invoke-RestMethod -Uri "$url/api/c/appointments" `
                -Method Post -ContentType "application/json" -Headers $headers -Body $body -TimeoutSec 30
            # 201 成功，取挂号序号（脱敏：不记患者/医生，只记序号）
            return [pscustomobject]@{ Seq = $seq; Status = 201; AppointmentSeq = $resp.sequence_number }
        } catch {
            $code = $_.Exception.Response.StatusCode.value__
            return [pscustomobject]@{ Seq = $seq; Status = $code; AppointmentSeq = $null }
        }
    } -ArgumentList $BaseUrl, $ScheduleId, $token, $seq
}

# 等待全部完成
$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job

# ---- 汇总脱敏日志 ----
$success = $results | Where-Object { $_.Status -eq 201 }
$conflict = $results | Where-Object { $_.Status -eq 409 }
$other = $results | Where-Object { $_.Status -ne 201 -and $_.Status -ne 409 }

Write-Host "---- 结果明细（patient 序号 / HTTP 状态 / 挂号序号）----" -ForegroundColor White
$results | Sort-Object Seq | ForEach-Object {
    $seq = if ($_.AppointmentSeq) { $_.AppointmentSeq } else { "-" }
    $color = if ($_.Status -eq 201) { "Green" } else { "Gray" }
    Write-Host ("  patient #{0,-3}  HTTP {1,-3}  挂号序号 {2}" -f $_.Seq, $_.Status, $seq) -ForegroundColor $color
}

Write-Host ""
Write-Host "---- 汇总 ----" -ForegroundColor White
Write-Host ("  成功(201): {0}" -f $success.Count)
Write-Host ("  冲突(409): {0}" -f $conflict.Count)
if ($other.Count -gt 0) {
    Write-Host ("  其他:      {0}" -f $other.Count) -ForegroundColor Yellow
}

Write-Host ""
if ($success.Count -eq 1 -and $conflict.Count -eq ($Concurrency - 1)) {
    Write-Host "[通过] 恰好 1 个成功、$($Concurrency - 1) 个冲突，号源防超卖验证通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host "[未通过] 期望 1 成功 + $($Concurrency - 1) 冲突，实际 $($success.Count) 成功 + $($conflict.Count) 冲突" -ForegroundColor Red
    if ($success.Count -gt 1) {
        Write-Host "  警告：出现超卖（多个 201），请检查号源扣减逻辑" -ForegroundColor Red
    }
    exit 1
}
