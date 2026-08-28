param(
    [switch]$Build,
    [switch]$Foreground
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot

if (-not (Test-Path -LiteralPath '.env')) {
    throw '缺少 .env。请先复制 .env.example 为 .env，并配置数据库密码、LLM Key 和 JWT 密钥。'
}

docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置校验失败。' }

if ($Foreground) {
    docker compose up --build
    exit $LASTEXITCODE
}

if ($Build) { docker compose up --build -d } else { docker compose up -d }
if ($LASTEXITCODE -ne 0) { throw '服务启动失败，请运行 docker compose logs 查看日志。' }

$deadline = (Get-Date).AddSeconds(90)
do {
    try {
        $health = Invoke-RestMethod -Uri 'http://localhost:8800/health' -TimeoutSec 3
        $page = Invoke-WebRequest -Uri 'http://localhost:9090/' -TimeoutSec 3 -UseBasicParsing
        if ($health.status -eq 'ok' -and $page.StatusCode -eq 200) {
            Write-Host '启动完成：用户端 http://localhost:9090/chat/  管理端 http://localhost:9090/admin/' -ForegroundColor Green
            exit 0
        }
    } catch { Start-Sleep -Seconds 2 }
} while ((Get-Date) -lt $deadline)

docker compose ps
throw '服务未在 90 秒内就绪，请执行 docker compose logs --tail=100 检查。'
