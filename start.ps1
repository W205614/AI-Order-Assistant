param(
    [switch]$Docker,
    [switch]$Build,
    [switch]$Foreground,
    [switch]$Detached,
    [switch]$Restart
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

function Stop-ProcessTree($Process) {
    if ($null -eq $Process -or $Process.HasExited) { return }
    & taskkill /PID $Process.Id /T /F *> $null
}

function Stop-PortListeners([int]$Port) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $processIds = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique)
        # 某些受限 Windows 会拒绝 Get-NetTCPConnection；netstat 是兼容回退。
        $netstatIds = @(netstat -ano -p tcp | Select-String -Pattern "^\s*TCP\s+.*:$Port\s+.*LISTENING\s+(\d+)\s*$" |
            ForEach-Object { [int]$_.Matches[0].Groups[1].Value })
        $processIds = @($processIds + $netstatIds | Select-Object -Unique | Where-Object { $_ -gt 0 })
        foreach ($listenerProcessId in $processIds) {
            & taskkill /PID $listenerProcessId /T /F *> $null
        }
        Start-Sleep -Milliseconds 700
        if (-not (Test-TcpPort $Port)) { return }
    }
    if (Test-TcpPort $Port) { throw "无法释放端口 $Port；请以管理员身份结束对应进程后重试。" }
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
# 默认由本脚本前台托管；Ctrl+C 会结束本次启动的两个进程树。
$agentDir = Join-Path $projectRoot 'agent-service'
$javaDir = Join-Path $projectRoot 'java-gateway'
if (-not (Test-Path -LiteralPath (Join-Path $agentDir '.env'))) { throw '本地模式需要 agent-service/.env。' }
if (-not (Test-Path -LiteralPath (Join-Path $javaDir 'src\main\resources\application.yml'))) { throw '本地模式需要 java-gateway/src/main/resources/application.yml。' }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw '未找到 Maven（mvn），请安装后重试。' }
foreach ($port in @(8800, 9090)) {
    if (Test-TcpPort $port) {
        if (-not $Restart) { throw "端口 $port 已被旧服务占用。请使用 .\start.ps1 -Restart 由脚本安全重启，或手动结束旧进程。" }
        Write-Host "正在结束占用端口 $port 的旧服务…" -ForegroundColor Yellow
        Stop-PortListeners $port
    }
}

$logsDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force -Path $logsDir *> $null
$agentProcess = $null
$gatewayProcess = $null
try {
    $agentProcess = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'run-agent.bat' -WorkingDirectory $agentDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logsDir 'agent.log') -RedirectStandardError (Join-Path $logsDir 'agent-error.log')
    $gatewayProcess = Start-Process -FilePath 'mvn.cmd' -ArgumentList 'spring-boot:run' -WorkingDirectory $javaDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logsDir 'gateway.log') -RedirectStandardError (Join-Path $logsDir 'gateway-error.log')
    Write-Host '正在启动本地 Agent 与 Java 网关（前端由网关托管）…'
    Wait-ForServices
    if ($Detached) {
        Write-Host '服务已转入后台；可通过 taskkill 或系统任务管理器停止。'
        exit 0
    }
    Write-Host '服务由此终端托管。按 Ctrl+C 将同时停止 Agent 和 Java 网关。日志位于 logs\。' -ForegroundColor Yellow
    while ($true) {
        if ($agentProcess.HasExited -or $gatewayProcess.HasExited) { throw '某个服务进程已退出，请查看 logs\ 中的日志。' }
        Start-Sleep -Seconds 1
    }
} finally {
    if (-not $Detached) {
        Stop-ProcessTree $gatewayProcess
        Stop-ProcessTree $agentProcess
    }
}
