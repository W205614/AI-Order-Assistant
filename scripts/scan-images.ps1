param([string]$ProjectName='ai-order-assistant',[string]$OutputDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) '.local/security'))
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$directory=(Resolve-Path -LiteralPath $OutputDirectory).Path
$scanner='aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa'
$container='ai-order-sbom-'+[guid]::NewGuid().ToString('N')
try{
    & docker create --name $container "${ProjectName}-gateway:latest" | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Gateway image unavailable'}
    & docker cp "${container}:/app/runtime-bom.json" (Join-Path $directory 'runtime-bom.json')
    if($LASTEXITCODE -ne 0){throw 'Gateway image does not contain its build SBOM'}
}finally{& docker rm $container | Out-Null}
foreach($service in @('gateway','agent')){
    $extra=if($service -eq 'gateway'){@('--pkg-types','os','--skip-files','**/*.jar')}else{@()}
    & docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v ai-order-trivy-cache:/cache -v "${directory}:/reports" $scanner image --quiet --cache-dir /cache --scanners vuln @extra --severity HIGH,CRITICAL --timeout 15m --format json --output "/reports/$service.json" "${ProjectName}-${service}:latest"
    if($LASTEXITCODE -ne 0){throw "Image/dependency scan failed for $service"}
}
& docker run --rm -v ai-order-trivy-cache:/cache -v "${directory}:/reports" $scanner sbom --quiet --cache-dir /cache --severity HIGH,CRITICAL --format json --output /reports/gateway-dependencies.json /reports/runtime-bom.json
if($LASTEXITCODE -ne 0){throw 'Java dependency SBOM scan failed'}
foreach($name in @('gateway','agent','gateway-dependencies')){
    $report=Get-Content -LiteralPath (Join-Path $directory "$name.json") -Raw | ConvertFrom-Json
    $fixed=@($report.Results | ForEach-Object {$_.Vulnerabilities} | Where-Object {$_.FixedVersion -and $_.Severity -in @('HIGH','CRITICAL')})
    if($fixed.Count){throw "$name has $($fixed.Count) fixable HIGH/CRITICAL findings; inspect the local report"}
}
Write-Host "No fixable HIGH/CRITICAL findings. Review all findings in $directory before public deployment."
