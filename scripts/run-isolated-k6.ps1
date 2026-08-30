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
    throw 'Missing root .env. The isolated load test still needs local LLM and service secrets.'
}

New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
Remove-Item -LiteralPath $summaryPath -Force -ErrorAction SilentlyContinue

Push-Location $projectRoot
try {
    & docker compose -p $projectName -f $composeFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Compose configuration validation failed.' }
    & docker compose -p $projectName -f $composeFile up --build -d
    if ($LASTEXITCODE -ne 0) { throw 'Isolated load-test services failed to start.' }

    $deadline = (Get-Date).AddSeconds(180)
    do {
        try {
            $health = Invoke-RestMethod -Uri 'http://localhost:18800/health' -TimeoutSec 5
            $gateway = Invoke-WebRequest -Uri 'http://localhost:19090/' -UseBasicParsing -TimeoutSec 5
            if ($health.status -eq 'ok' -and $gateway.StatusCode -eq 200) { break }
        } catch { Start-Sleep -Seconds 2 }
    } while ((Get-Date) -lt $deadline)
    if ((Get-Date) -ge $deadline) { throw 'Isolated load-test services did not become ready within 180 seconds.' }

    $k6Environment = @(
        '--env', 'BASE_URL=http://host.docker.internal:19090',
        '--env', "READ_VUS=$ReadVus",
        '--env', "READ_DURATION=$ReadDuration",
        '--env', "WRITE_VUS=$WriteVus",
        '--env', 'K6_SUMMARY_PATH=/results/k6-summary.json'
    )
    if ($RunWrites) { $k6Environment += @('--env', 'RUN_WRITES=true') }
    if ($ConfirmOrders) {
        if (-not $RunWrites) { throw '-ConfirmOrders requires -RunWrites.' }
        $k6Environment += @('--env', 'RUN_CONFIRM=true')
    }

    & docker run --rm --add-host 'host.docker.internal:host-gateway' `
        -v "${projectRoot}\load:/scripts:ro" -v "${resultDirectory}:/results" `
        @k6Environment grafana/k6:0.54.0 run /scripts/k6-order-flow.js
    if ($LASTEXITCODE -ne 0) { throw 'k6 threshold or request checks failed.' }
    if (-not (Test-Path $summaryPath)) { throw 'k6 did not generate a summary file.' }
    Write-Host "Isolated load test completed: $summaryPath"
} finally {
    # This command only removes the `ai-order-perf` containers and perf-* volumes.
    & docker compose -p $projectName -f $composeFile down -v
    Pop-Location
}
