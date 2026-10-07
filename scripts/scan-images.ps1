param([string]$ProjectName='ai-order-assistant',[string]$OutputDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) '.local/security'))
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$directory=(Resolve-Path -LiteralPath $OutputDirectory).Path
foreach($service in @('gateway','agent')){
    & docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v ai-order-trivy-cache:/cache -v "${directory}:/reports" aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa image --quiet --cache-dir /cache --scanners vuln --severity HIGH,CRITICAL --timeout 15m --format json --output "/reports/$service.json" "${ProjectName}-${service}:latest"
    if($LASTEXITCODE -ne 0){throw "Image/dependency scan failed for $service"}
    $report=Get-Content -LiteralPath (Join-Path $directory "$service.json") -Raw | ConvertFrom-Json
    $fixed=@($report.Results | ForEach-Object {$_.Vulnerabilities} | Where-Object {$_.FixedVersion -and $_.Severity -in @('HIGH','CRITICAL')})
    if($fixed.Count){throw "$service has $($fixed.Count) fixable HIGH/CRITICAL findings; inspect the local report"}
}
Write-Host "No fixable HIGH/CRITICAL findings. Review all findings in $directory before public deployment."
