param(
    [string]$ProjectName='ai-order-assistant',
    [string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),
    [string]$BackupDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) 'backups'),
    [double]$MaxBackupAgeHours=30,
    [string]$ReportPath
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$issues=[Collections.Generic.List[string]]::new()
$services=[Collections.Generic.List[object]]::new()
$backup=[ordered]@{status='unknown';lastSuccessAt=0L;ageSeconds=$null;extraCopyVerified=$false}
try {
    if($MaxBackupAgeHours -le 0 -or [double]::IsNaN($MaxBackupAgeHours) -or [double]::IsInfinity($MaxBackupAgeHours)){throw 'Invalid maximum backup age'}
    # ps exposes only selected state below; never emit Compose config or environment.
    $raw=& docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') ps --all --format json 2>$null
    if($LASTEXITCODE -ne 0){throw 'Container state unavailable'}
    $containers=@()
    $joined=($raw -join "`n").Trim()
    if($joined.StartsWith('[')){$containers=@($joined|ConvertFrom-Json)}
    elseif($joined){$containers=@($raw|ForEach-Object {if($_.Trim()){$_|ConvertFrom-Json}})}
    foreach($service in @('mysql','redis','gateway','agent','backup','prometheus')){
        $matches=@($containers|Where-Object {$_.Service -eq $service})
        $healthy=$service -in @('mysql','redis','gateway','agent')
        $ok=$matches.Count -gt 0
        foreach($container in $matches){
            if($container.State -ne 'running' -or ($healthy -and $container.Health -ne 'healthy')){$ok=$false}
        }
        $services.Add([ordered]@{name=$service;passed=$ok;replicas=$matches.Count})
        if(-not $ok){$issues.Add('service_unhealthy:'+ $service)}
    }
} catch {$issues.Add('container_check_failed')}
try {
    $state=[IO.File]::ReadAllText((Join-Path $BackupDirectory 'backup-status.json'))|ConvertFrom-Json
    $lastSuccess=[long]$state.lastSuccessAt
    $lastAttempt=[long]$state.lastAttemptAt
    if($state.schemaVersion -ne 1 -or $lastSuccess -le 0 -or $lastAttempt -le 0 -or $lastAttempt -gt $now+300){throw 'Invalid backup status'}
    $age=$now-$lastSuccess
    $backup.status=[string]$state.status
    $backup.lastSuccessAt=$lastSuccess
    $backup.ageSeconds=$age
    $backup.extraCopyVerified=[bool]$state.extraCopyVerified
    if($state.status -ne 'ok'){$issues.Add('backup_last_attempt_failed')}
    if($age -lt -300 -or $age -gt $MaxBackupAgeHours*3600){$issues.Add('backup_stale')}
} catch {$issues.Add('backup_status_unavailable')}
$report=[ordered]@{schemaVersion=1;checkedAt=$now;project=$ProjectName;passed=($issues.Count -eq 0);services=@($services.ToArray());backup=$backup;issues=@($issues.ToArray())}
$json=$report|ConvertTo-Json -Depth 6 -Compress
if($ReportPath){
    try {
        $target=[IO.Path]::GetFullPath($ReportPath)
        $parent=Split-Path -Parent $target
        New-Item -ItemType Directory -Force -Path $parent|Out-Null
        [IO.File]::WriteAllText($target,$json,[Text.UTF8Encoding]::new($false))
    } catch {
        $report.passed=$false
        $report.issues=@($report.issues)+@('report_write_failed')
        $json=$report|ConvertTo-Json -Depth 6 -Compress
    }
}
Write-Output $json
if(-not $report.passed){exit 1}
