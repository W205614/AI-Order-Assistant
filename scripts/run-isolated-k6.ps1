param(
    [int]$ReadVus = 3,
    [string]$ReadDuration = '30s',
    [int]$WriteVus = 2,
    [switch]$RunWrites,
    [switch]$ConfirmOrders
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'docker-compose.perf.yml'
$resultDirectory = Join-Path $projectRoot 'load\results'
$summaryPath = Join-Path $resultDirectory 'k6-summary.json'
$projectName = 'ai-order-perf'

if (-not (Test-Path (Join-Path $projectRoot '.env'))) {
    throw '缺少根目录 .env；隔离压测仍需要本地 LLM 与服务密钥。'
}

New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
Remove-Item -LiteralPath $summaryPath -Force -ErrorAction SilentlyContinue

Push-Location $projectRoot
try {
    & docker compose -p $projectName -f $composeFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw '隔离 Compose 配置校验失败。' }
    & docker compose -p $projectName -f $composeFile up --build -d
    if ($LASTEXITCODE -ne 0) { throw '隔离压测服务启动失败。' }

    $deadline = (Get-Date).AddSeconds(180)
    do {
        try {
            $health = Invoke-RestMethod -Uri 'http://localhost:18800/health' -TimeoutSec 5
            $gateway = Invoke-WebRequest -Uri 'http://localhost:19090/' -UseBasicParsing -TimeoutSec 5
            if ($health.status -eq 'ok' -and $gateway.StatusCode -eq 200) { break }
        } catch { Start-Sleep -Seconds 2 }
    } while ((Get-Date) -lt $deadline)
    if ((Get-Date) -ge $deadline) { throw '隔离压测服务未在 180 秒内就绪。' }

    $k6Environment = @(
        '--env', 'BASE_URL=http://host.docker.internal:19090',
        '--env', "READ_VUS=$ReadVus",
        '--env', "READ_DURATION=$ReadDuration",
        '--env', "WRITE_VUS=$WriteVus",
        '--env', 'K6_SUMMARY_PATH=/results/k6-summary.json'
    )
    if ($RunWrites) { $k6Environment += @('--env', 'RUN_WRITES=true') }
    if ($ConfirmOrders) {
        if (-not $RunWrites) { throw '-ConfirmOrders 需要同时指定 -RunWrites。' }
        $k6Environment += @('--env', 'RUN_CONFIRM=true')
    }

    & docker run --rm --add-host 'host.docker.internal:host-gateway' `
        -v "${projectRoot}\load:/scripts:ro" -v "${resultDirectory}:/results" `
        @k6Environment grafana/k6:0.54.0 run /scripts/k6-order-flow.js
    if ($LASTEXITCODE -ne 0) { throw 'k6 阈值或请求校验失败。' }
    if (-not (Test-Path $summaryPath)) { throw 'k6 未生成结果文件。' }
    Write-Host "隔离压测完成：$summaryPath"
} finally {
    # This command only removes the `ai-order-perf` containers and perf-* volumes.
    & docker compose -p $projectName -f $composeFile down -v
    Pop-Location
}
