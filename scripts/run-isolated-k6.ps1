param([int]$Vus=50,[string]$Duration='10m',[switch]$LeaveRunning,[string]$Python='python')
$ErrorActionPreference='Stop'
if($Vus -lt 1 -or $Vus -gt 50){throw 'Vus must be 1–50'}
$root=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$envFile=Join-Path $root '.github/compose-ci.env'
$compose=@('compose','--env-file',$envFile,'-p','ai-order-perf','-f','docker-compose.yml','-f','docker-compose.perf.yml')
$results=Join-Path $root 'load/results'
$fixtures=Join-Path $root '.local/perf'
New-Item -ItemType Directory -Force -Path $results,$fixtures | Out-Null
try{
    & docker @compose up -d --wait mysql redis
    if($LASTEXITCODE -ne 0){throw 'Perf dependencies failed'}
    & (Join-Path $PSScriptRoot 'provision-db-user.ps1') -ProjectName ai-order-perf -EnvPath $envFile
    & docker @compose up --build -d --wait --wait-timeout 180
    if($LASTEXITCODE -ne 0){throw 'Perf application failed readiness'}
    & $Python scripts/prepare-load-users.py --project ai-order-perf --count $Vus --output "$fixtures/bench-users.json"
    if($LASTEXITCODE -ne 0){throw 'Could not prepare independent sessions'}
    & docker run --rm --network ai-order-perf_default -v "${root}/load:/scripts:ro" -v "${fixtures}:/fixtures:ro" -v "${results}:/results" -e BASE_URL=http://gateway:9090 -e "VUS=$Vus" -e "DURATION=$Duration" grafana/k6@sha256:a98db15eb83dfc4dbded9653a15f53557c011400019eba715a9cd15b0ab709d9 run /scripts/k6-order-flow.js
    if($LASTEXITCODE -ne 0){throw 'k6 request/consistency/performance thresholds failed'}
    & $Python scripts/check-load-invariants.py --project ai-order-perf --env-file $envFile --output "$results/stock-invariants.json"
    if($LASTEXITCODE -ne 0){throw 'Post-load inventory invariants failed'}
    Write-Host "Isolated load test passed: $results"
}finally{
    if(-not $LeaveRunning){& docker @compose down -v}
}
