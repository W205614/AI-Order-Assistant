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

function Get-ListenerProcessIds([int]$Port) {
    $processIds = @()
    try {
        $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        foreach ($connection in $connections) {
            if ($connection.OwningProcess -gt 0) { $processIds += [int]$connection.OwningProcess }
        }
    } catch { }
    # netstat is a fallback for restricted Windows sessions.
    try {
        foreach ($line in @(netstat -ano -p tcp)) {
            if ($line -match ("^\s*TCP\s+.*:{0}\s+.*LISTENING\s+(\d+)\s*$" -f $Port)) {
                $processIds += [int]$matches[1]
            }
        }
    } catch { }
    return @($processIds | Select-Object -Unique | Where-Object { $_ -gt 0 })
}

function Stop-PortListeners([int]$Port) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $listenerProcessIds = @(Get-ListenerProcessIds $Port)
        foreach ($listenerProcessId in $listenerProcessIds) {
            & taskkill /PID $listenerProcessId /T /F *> $null
        }
        Start-Sleep -Milliseconds 700
        if (-not (Test-TcpPort $Port)) { return $true }
    }
    return $false
}

function Find-FreeAgentPort {
    for ($candidate = 8801; $candidate -le 8899; $candidate++) {
        if (-not (Test-TcpPort $candidate)) { return $candidate }
    }
    throw 'No available Agent port in 8801-8899.'
}

function Wait-ForServices([int]$AgentPort = 8800) {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        if ((Test-HttpOk "http://localhost:${AgentPort}/health") -and (Test-HttpOk 'http://localhost:9090/')) {
            Write-Host 'Ready: user UI http://localhost:9090/chat/  admin UI http://localhost:9090/admin/' -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Services did not become ready within 90 seconds. Check agent-service and java-gateway logs.'
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
        throw 'Docker mode needs a root .env or agent-service/.env file.'
    }
    $agentValues = @{}
    Get-Content -LiteralPath $agentEnvPath | ForEach-Object {
        if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*)\s*$') {
            $agentValues[$matches[1].Trim()] = $matches[2].Trim().Trim('"').Trim("'")
        }
    }
    foreach ($name in @('LLM_API_KEY', 'AGENT_INTERNAL_API_KEY')) {
        if ([string]::IsNullOrWhiteSpace($agentValues[$name])) {
            throw "agent-service/.env is missing $name; cannot generate Compose configuration."
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
    Write-Host 'Created root .env for Docker Compose from agent-service/.env.' -ForegroundColor Yellow
}

if ($Docker) {
    Initialize-ComposeEnv
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running, or is not using Linux containers.' }
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose configuration validation failed.' }
    if ($Foreground) { docker compose up --build; exit $LASTEXITCODE }
    if ($Build) { docker compose up --build -d } else { docker compose up -d }
    if ($LASTEXITCODE -ne 0) { throw 'Docker service startup failed. Run docker compose logs for details.' }
    Wait-ForServices
    exit 0
}

# Local mode reuses existing MySQL, agent-service/.env, and Java application.yml.
# This terminal owns the two child process trees unless -Detached is supplied.
$agentDir = Join-Path $projectRoot 'agent-service'
$javaDir = Join-Path $projectRoot 'java-gateway'
if (-not (Test-Path -LiteralPath (Join-Path $agentDir '.env'))) { throw 'Local mode needs agent-service/.env.' }
if (-not (Test-Path -LiteralPath (Join-Path $javaDir 'src\main\resources\application.yml'))) { throw 'Local mode needs java-gateway/src/main/resources/application.yml.' }
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($null -eq $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue }
if ($null -eq $mavenCommand) { throw 'Maven (mvn) was not found.' }
$condaBase = (& conda info --base 2>$null | Select-Object -First 1).Trim()
if ([string]::IsNullOrWhiteSpace($condaBase)) { throw 'Conda was not found; cannot locate the ai-order-agent environment.' }
$agentPython = Join-Path $condaBase 'envs\ai-order-agent\python.exe'
if (-not (Test-Path -LiteralPath $agentPython)) { throw "Agent Python environment was not found: $agentPython" }

$agentPort = 8800
if (Test-TcpPort $agentPort) {
    if (-not $Restart) { throw 'Port 8800 is in use. Use .\start.ps1 -Restart or stop the previous Agent process.' }
    Write-Host 'Stopping the previous Agent on port 8800...' -ForegroundColor Yellow
    if (-not (Stop-PortListeners $agentPort)) {
        $agentPort = Find-FreeAgentPort
        Write-Host "The previous Agent could not be stopped; using port $agentPort for this run." -ForegroundColor Yellow
    }
}
if (Test-TcpPort 9090) {
    if (-not $Restart) { throw 'Port 9090 is in use. Use .\start.ps1 -Restart or stop the previous Java gateway process.' }
    Write-Host 'Stopping the previous Java gateway on port 9090...' -ForegroundColor Yellow
    if (-not (Stop-PortListeners 9090)) { throw 'Unable to release port 9090. Stop the listener with an elevated terminal and retry.' }
}

$logsDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force -Path $logsDir *> $null
$agentProcess = $null
$gatewayProcess = $null
try {
    $agentProcess = Start-Process -FilePath $agentPython -ArgumentList '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', $agentPort -WorkingDirectory $agentDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logsDir 'agent.log') -RedirectStandardError (Join-Path $logsDir 'agent-error.log')
    $previousAgentBaseUrl = $env:AI_AGENT_BASE_URL
    $env:AI_AGENT_BASE_URL = "http://localhost:${agentPort}"
    try {
        $gatewayProcess = Start-Process -FilePath $mavenCommand.Source -ArgumentList 'spring-boot:run' -WorkingDirectory $javaDir -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logsDir 'gateway.log') -RedirectStandardError (Join-Path $logsDir 'gateway-error.log')
    } finally {
        if ($null -eq $previousAgentBaseUrl) { Remove-Item Env:AI_AGENT_BASE_URL -ErrorAction SilentlyContinue }
        else { $env:AI_AGENT_BASE_URL = $previousAgentBaseUrl }
    }
    Write-Host "Starting local Agent on $agentPort and Java gateway on 9090..."
    Wait-ForServices $agentPort
    if ($Detached) {
        Write-Host 'Services are running in the background. Stop them with taskkill or Task Manager.'
        exit 0
    }
    Write-Host 'This terminal owns both services. Press Ctrl+C to stop them. Logs are in logs\.' -ForegroundColor Yellow
    while ($true) {
        if ($agentProcess.HasExited -or $gatewayProcess.HasExited) { throw 'A service process exited. Check logs\ for details.' }
        Start-Sleep -Seconds 1
    }
} finally {
    if (-not $Detached) {
        Stop-ProcessTree $gatewayProcess
        Stop-ProcessTree $agentProcess
    }
}
