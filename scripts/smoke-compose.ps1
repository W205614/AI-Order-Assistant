param([switch]$LeaveRunning,[string]$Python='python')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
# Preserve the local project's data; smoke cleanup only cancels its own orders.
& (Join-Path $root 'start.ps1') -Docker -Build
& $Python scripts/test-functional-concurrency.py --base-url http://127.0.0.1:9090 --report .local/smoke-report.json
if($LASTEXITCODE -ne 0){throw 'Cookie/CSRF and order concurrency smoke failed'}
if(-not $LeaveRunning){docker compose -p ai-order-assistant stop gateway agent}
