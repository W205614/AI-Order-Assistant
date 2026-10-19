param(
    [string]$ProjectName='ai-order-assistant',
    [string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),
    [string]$BackupDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) 'backups'),
    [double]$MaxBackupAgeHours=30,
    [string]$PublicBaseUrl,
    [string]$ReportPath
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$issues=[Collections.Generic.List[string]]::new()
$services=[Collections.Generic.List[object]]::new()
$backup=[ordered]@{status='unknown';lastSuccessAt=0L;ageSeconds=$null;extraCopyVerified=$false;artifact=[ordered]@{status='not_checked';sizeBytes=0L;checksumVerified=$false}}
$mode=if($PublicBaseUrl){'public'}else{'local'}
$public=[ordered]@{status='not_checked';passed=$null;tlsVerified=$false;page='not_checked';readiness='not_checked'}

function Assert-OrdinaryPath([string]$Path){
    # Reject symlinks/junctions in every existing component, including the configured root.
    # GetFullPath alone normalizes '..' but does not resolve reparse points.
    $current=[IO.Path]::GetFullPath($Path)
    while($current){
        $entry=Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if(($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Unsafe backup path'}
        $parent=Split-Path -Parent $current
        if($parent -eq $current){break}
        $current=$parent
    }
}
try {
    if($MaxBackupAgeHours -le 0 -or [double]::IsNaN($MaxBackupAgeHours) -or [double]::IsInfinity($MaxBackupAgeHours)){throw 'Invalid maximum backup age'}
    # ps exposes only selected state below; never emit Compose config or environment.
    $raw=& docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') ps --all --format json 2>$null
    if($LASTEXITCODE -ne 0){throw 'Container state unavailable'}
    $containers=@()
    $joined=($raw -join "`n").Trim()
    if($joined.StartsWith('[')){$containers=@($joined|ConvertFrom-Json)}
    elseif($joined){$containers=@($raw|ForEach-Object {if($_.Trim()){$_|ConvertFrom-Json}})}
    $required=@('mysql','redis','gateway','agent','backup','prometheus')
    if($PublicBaseUrl){$required+=@('proxy')}
    foreach($service in $required){
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
    $backupRoot=[IO.Path]::GetFullPath($BackupDirectory)
    $statePath=Join-Path $backupRoot 'backup-status.json'
    Assert-OrdinaryPath $statePath
    $state=[IO.File]::ReadAllText($statePath)|ConvertFrom-Json
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
    try {
        $name=[string]$state.backupFile
        if(-not $name -or $name -match '[\\/:]' -or $name -notmatch '\.sql$' -or [IO.Path]::IsPathRooted($name)){
            $backup.artifact.status='unsafe';throw 'backup_artifact_unsafe'
        }
        $dumpPath=[IO.Path]::GetFullPath((Join-Path $backupRoot $name))
        $prefix=$backupRoot.TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
        $comparison=if([Environment]::OSVersion.Platform -eq [PlatformID]::Unix){[StringComparison]::Ordinal}else{[StringComparison]::OrdinalIgnoreCase}
        if(-not $dumpPath.StartsWith($prefix,$comparison)){$backup.artifact.status='unsafe';throw 'backup_artifact_unsafe'}
        if(-not [IO.File]::Exists($dumpPath) -or -not [IO.File]::Exists($dumpPath+'.sha256')){
            $backup.artifact.status='missing';throw 'backup_artifact_missing'
        }
        try {Assert-OrdinaryPath $dumpPath;Assert-OrdinaryPath ($dumpPath+'.sha256')}
        catch {$backup.artifact.status='unsafe';throw 'backup_artifact_unsafe'}
        $length=(Get-Item -LiteralPath $dumpPath).Length
        $backup.artifact.sizeBytes=$length
        if($length -le 0){$backup.artifact.status='empty';throw 'backup_artifact_empty'}
        $expected=[string]$state.sha256
        $sidecar=[IO.File]::ReadAllText($dumpPath+'.sha256').Trim()
        if($expected -notmatch '^[a-fA-F0-9]{64}$' -or $sidecar -notmatch '^[a-fA-F0-9]{64}$' -or $expected -ne $sidecar -or (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash -ne $expected){
            $backup.artifact.status='checksum_mismatch';throw 'backup_checksum_mismatch'
        }
        $backup.artifact.status='verified'
        $backup.artifact.checksumVerified=$true
    } catch {
        $code=[string]$_.Exception.Message
        if($code -notin @('backup_artifact_unsafe','backup_artifact_missing','backup_artifact_empty','backup_checksum_mismatch')){$code='backup_artifact_unavailable';$backup.artifact.status='unavailable'}
        $issues.Add($code)
    }
} catch {$issues.Add('backup_status_unavailable')}
if($PublicBaseUrl){
    $public.passed=$false
    $public.status='failed'
    try {
        $uri=[Uri]$PublicBaseUrl
        if(-not $uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/'){
            throw 'Invalid public origin'
        }
        # No certificate bypass, redirects, cookies, credentials, or business writes.
        $origin=$uri.GetLeftPart([UriPartial]::Authority)
        try {
            $page=Invoke-WebRequest -Uri ($origin+'/campus/index.html') -UseBasicParsing -TimeoutSec 10 -MaximumRedirection 0
            if([int]$page.StatusCode -ne 200 -or [string]$page.Content -notmatch '(?i)<(?:!doctype\s+html|html\b)'){throw 'Invalid page'}
            $public.page='passed'
        } catch {$public.page='failed';$issues.Add('public_page_failed')}
        try {
            $ready=Invoke-WebRequest -Uri ($origin+'/actuator/health/readiness') -UseBasicParsing -TimeoutSec 10 -MaximumRedirection 0
            if([int]$ready.StatusCode -ne 200 -or ($ready.Content|ConvertFrom-Json).status -ne 'UP'){throw 'Invalid readiness'}
            $public.readiness='passed'
        } catch {$public.readiness='failed';$issues.Add('public_readiness_failed')}
        $public.tlsVerified=($public.page -eq 'passed' -and $public.readiness -eq 'passed')
        $proxyOk=@($services|Where-Object {$_.name -eq 'proxy' -and $_.passed}).Count -eq 1
        $public.passed=($public.tlsVerified -and $proxyOk)
        if($public.passed){$public.status='passed'}
    } catch {$issues.Add('public_url_invalid')}
}
$report=[ordered]@{schemaVersion=1;checkedAt=$now;project=$ProjectName;mode=$mode;passed=($issues.Count -eq 0);services=@($services.ToArray());backup=$backup;public=$public;issues=@($issues.ToArray())}
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
