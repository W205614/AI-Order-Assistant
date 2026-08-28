param(
    [switch]$Docker,
    [switch]$Build,
    [switch]$Foreground
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot

function Test-HttpOk([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -TimeoutSec 8 -UseBasicParsing
        return ($response.StatusCode -eq 200)
    } catch { return $false }
}

function Test-TcpPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connect = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($connect)
        return $true
    } catch { return $false }
    finally { $client.Dispose() }
}

function Wait-ForServices {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        if ((Test-HttpOk 'http://localhost:8800/health') -and (Test-HttpOk 'http://localhost:9090/')) {
            Write-Host '启动完成：用户端 http://localhost:9090/chat/  管理端 http://localhost:9090/admin/' -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw '服务未在 90 秒内就绪。请分别检查 agent-service 与 java-gateway 的启动日志。'
}

function New-LocalSecret {
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

function Initialize-ComposeEnv {
    if (Test-Path -LiteralPath '.env') { return }
    $agentEnvPath = Join-Path $projectRoot 'agent-service\.env'
    if (-not (Test-Path -LiteralPath $agentEnvPath)) {
        throw 'Docker 模式需要根目录 .env 或 agent-service/.env。请先配置其中之一。'
    }
    $agentValues = @{}
    Get-Content -LiteralPath $agentEnvPath | ForEach-Object {
        if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*)\s*$') {
            $agentValues[$matches[1].Trim()] = $matches[2].Trim().Trim('"').Trim("'")
        }
    }
    foreach ($name in @('LLM_API_KEY', 'AGENT_INTERNAL_API_KEY')) {
        if ([string]::IsNullOrWhiteSpace($agentValues[$name])) {
            throw "agent-service/.env 缺少 $name，无法生成 Docker Compose 所需配置。"
        }
    }
    $llmBaseUrl = $agentValues['LLM_BASE_URL']; if ([string]::IsNullOrWhiteSpace($llmBaseUrl)) { $llmBaseUrl = 'https://api.openai.com/v1' }
    $llmModel = $agentValues['LLM_MODEL']; if ([string]::IsNullOrWhiteSpace($llmModel)) { $llmModel = 'gpt-4o-mini' }
    Set-Content -LiteralPath '.env' -Encoding utf8 -Value @(
        "MYSQL_ROOT_PASSWORD=$(New-LocalSecret)", "LLM_API_KEY=$($agentValues['LLM_API_KEY'])",
        "LLM_BASE_URL=$llmBaseUrl", "LLM_MODEL=$llmModel",
        "AGENT_INTERNAL_API_KEY=$($agentValues['AGENT_INTERNAL_API_KEY'])",
        "JWT_USER_SECRET=$(New-LocalSecret)", "JWT_ADMIN_SECRET=$(New-LocalSecret)"
    )
    Write-Host '已根据 agent-service/.env 创建 Docker Compose 用的根目录 .env。' -ForegroundColor Yellow
}

if ($Docker) {
    Initialize-ComposeEnv
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop 未启动，或未切换到 Linux containers。请启动 Docker Desktop 后重试。' }
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置校验失败。' }
    if ($Foreground) { docker compose up --build; exit $LASTEXITCODE }
    if ($Build) { docker compose up --build -d } else { docker compose up -d }
    if ($LASTEXITCODE -ne 0) { throw 'Docker 服务启动失败，请运行 docker compose logs 查看日志。' }
    Wait-ForServices
    exit 0
}

# 默认本地模式：复用已有的 MySQL、Java application.yml 与 agent-service/.env。
$agentDir = Join-Path $projectRoot 'agent-service'
$javaDir = Join-Path $projectRoot 'java-gateway'
if (-not (Test-Path -LiteralPath (Join-Path $agentDir '.env'))) { throw '本地模式需要 agent-service/.env。' }
if (-not (Test-Path -LiteralPath (Join-Path $javaDir 'src\main\resources\application.yml'))) { throw '本地模式需要 java-gateway/src/main/resources/application.yml。' }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw '未找到 Maven（mvn），请安装后重试。' }

if (-not (Test-TcpPort 8800)) {
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'run-agent.bat' -WorkingDirectory $agentDir -WindowStyle Hidden
    Write-Host '正在启动本地 Agent…'
} else { Write-Host 'Agent 已在运行，跳过。' }

if (-not (Test-TcpPort 9090)) {
    Start-Process -FilePath 'mvn.cmd' -ArgumentList 'spring-boot:run' -WorkingDirectory $javaDir -WindowStyle Hidden
    Write-Host '正在启动本地 Java 网关（前端由它托管）…'
} else { Write-Host 'Java 网关已在运行，跳过。' }

Wait-ForServices
