param(
    [switch]$Build,
    [switch]$Foreground
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot

if (-not (Test-Path -LiteralPath '.env')) {
    $agentEnvPath = Join-Path $projectRoot 'agent-service\.env'
    if (-not (Test-Path -LiteralPath $agentEnvPath)) {
        throw '根目录 .env 与 agent-service/.env 均不存在。请先配置 agent-service/.env，或复制 .env.example 为根目录 .env。'
    }

    $agentValues = @{}
    Get-Content -LiteralPath $agentEnvPath | ForEach-Object {
        if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*)\s*$') {
            $agentValues[$matches[1].Trim()] = $matches[2].Trim().Trim('"').Trim("'")
        }
    }
    foreach ($name in @('LLM_API_KEY', 'AGENT_INTERNAL_API_KEY')) {
        if ([string]::IsNullOrWhiteSpace($agentValues[$name])) {
            throw "agent-service/.env 缺少 $name，无法生成 Docker Compose 所需的根目录 .env。"
        }
    }
    function New-LocalSecret {
        $bytes = New-Object byte[] 48
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        return [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
    }
    $llmBaseUrl = $agentValues['LLM_BASE_URL']
    if ([string]::IsNullOrWhiteSpace($llmBaseUrl)) { $llmBaseUrl = 'https://api.openai.com/v1' }
    $llmModel = $agentValues['LLM_MODEL']
    if ([string]::IsNullOrWhiteSpace($llmModel)) { $llmModel = 'gpt-4o-mini' }
    $composeEnv = @(
        "MYSQL_ROOT_PASSWORD=$(New-LocalSecret)",
        "LLM_API_KEY=$($agentValues['LLM_API_KEY'])",
        "LLM_BASE_URL=$llmBaseUrl",
        "LLM_MODEL=$llmModel",
        "AGENT_INTERNAL_API_KEY=$($agentValues['AGENT_INTERNAL_API_KEY'])",
        "JWT_USER_SECRET=$(New-LocalSecret)",
        "JWT_ADMIN_SECRET=$(New-LocalSecret)"
    )
    Set-Content -LiteralPath '.env' -Value $composeEnv -Encoding utf8
    Write-Host '已根据 agent-service/.env 创建根目录 .env，并生成本地 MySQL/JWT 密钥。该文件已被 Git 忽略。' -ForegroundColor Yellow
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
