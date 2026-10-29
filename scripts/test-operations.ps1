# No real Docker calls: fixtures and backups live only in a unique temporary folder.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'backup-process.ps1')
$root=Split-Path -Parent $PSScriptRoot
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('ai-order-ops-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary|Out-Null
$primary=Join-Path $temporary 'primary'
$extra=Join-Path $temporary 'extra'
$fakeEnv=Join-Path $temporary 'fixture.env'
$defaultReport=Join-Path $root '.local/operations-report.json'
$oldReportHash=if(Test-Path -LiteralPath $defaultReport){(Get-FileHash -LiteralPath $defaultReport).Hash}else{$null}
[IO.File]::WriteAllText($fakeEnv,('BACKUP_EXTRA_DIRECTORY="'+$extra+'"'))
$global:opsTest=@{mode='success';dockerCalls=$null;webCalls=$null;containers=$null;checkCode=0}
$global:opsTest.dockerCalls=[Collections.Generic.List[string]]::new()
$global:opsTest.webCalls=[Collections.Generic.List[string]]::new()
$global:opsTest.containers=@('mysql','redis','gateway','agent','backup','prometheus')|ForEach-Object {@{Service=$_;State='running';Health=if($_ -in @('mysql','redis','gateway','agent','backup')){'healthy'}else{''}}}
function Assert-True($Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Copy-Item {
    param([string]$LiteralPath,[string]$Destination)
    if($global:opsTest.mode -eq 'extra-failed'){throw 'Fixture additional copy unavailable'}
    Microsoft.PowerShell.Management\Copy-Item -LiteralPath $LiteralPath -Destination $Destination
}
function docker {
    $global:opsTest.dockerCalls.Add(($args -join ' '))
    $global:LASTEXITCODE=0
    if($args[0] -eq 'cp'){
        [IO.File]::WriteAllText([string]$args[2],('-- fixture '+('x'*150)+"`n-- Dump completed`n"))
    } elseif($args -contains 'ps'){
        if($args -contains '--format'){
            if($global:opsTest.mode -eq 'docker-failed'){$global:LASTEXITCODE=1;return}
            $global:opsTest.containers|ForEach-Object {$_|ConvertTo-Json -Compress}
        } else {'fixture-mysql'}
    } elseif($args -contains 'exec'){
        if($global:opsTest.mode -eq 'dump-failed' -and $args[-1] -match 'mysqldump'){$global:LASTEXITCODE=1}
    } else {throw 'Unexpected Docker invocation in isolated test'}
}
function Invoke-WebRequest {
    param([string]$Uri,[switch]$UseBasicParsing,[int]$TimeoutSec,[int]$MaximumRedirection)
    Assert-True ($Uri.StartsWith('https://operations.invalid/') -and $TimeoutSec -eq 10 -and $MaximumRedirection -eq 0 -and $UseBasicParsing) 'Unsafe public request contract'
    $global:opsTest.webCalls.Add($Uri)
    if($global:opsTest.mode -eq 'web-timeout'){throw [TimeoutException]::new('Fixture timeout')}
    if($global:opsTest.mode -eq 'web-failed'){throw 'Fixture TLS/transport failure'}
    if($Uri.EndsWith('/readiness')){
        return @{StatusCode=200;Content=if($global:opsTest.mode -eq 'not-ready'){'{"status":"DOWN"}'}else{'{"status":"UP"}'}}
    }
    return @{StatusCode=if($global:opsTest.mode -eq 'page-failed'){503}else{200};Content='<!DOCTYPE html><html></html>'}
}
# Backup process execution is injected at the boundary; real timeout/process-tree
# behavior is covered separately by test-backup-process.ps1 and Linux shell tests.
$backupRunner={
    param($Executable,$Arguments,$Seconds)
    if($Executable -eq 'docker'){
        $output=docker @Arguments
        return [pscustomobject]@{ExitCode=$global:LASTEXITCODE;Output=($output -join "`n");Error=''}
    }
    $source=$Arguments[[Array]::IndexOf($Arguments,'-Source')+1]
    $destination=$Arguments[[Array]::IndexOf($Arguments,'-Destination')+1]
    try {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination)|Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
        [IO.File]::WriteAllText(($destination+'.sha256'),(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant())
        return [pscustomobject]@{ExitCode=0;Output='';Error=''}
    }
    catch {return [pscustomobject]@{ExitCode=1;Output='';Error='Fixture copy failure'}}
}
function Invoke-FixtureBackup {
    & (Join-Path $PSScriptRoot 'backup-db.ps1') -ProcessRunner $backupRunner @args
}
function Invoke-Check {
    $global:LASTEXITCODE=0
    $output=& (Join-Path $PSScriptRoot 'check-operations.ps1') -BackupDirectory $primary -EnvPath $fakeEnv @args
    $global:opsTest.checkCode=$LASTEXITCODE
    return ($output|ConvertFrom-Json)
}
try {
    $disabled=Join-Path $temporary 'disabled'
    New-Item -ItemType Directory -Path $disabled|Out-Null
    [IO.File]::WriteAllText((Join-Path $disabled '.backup-disabled'),'')
    $callCount=$global:opsTest.dockerCalls.Count
    & (Join-Path $PSScriptRoot 'backup-db.ps1') -OutputDirectory $disabled -EnvPath (Join-Path $temporary 'missing.env') -ExtraBackupDirectory (Join-Path $temporary 'disabled-extra') -ProcessRunner {throw 'Disabled backup started a worker'}
    Assert-True ($global:opsTest.dockerCalls.Count -eq $callCount -and @(Get-ChildItem -LiteralPath $disabled -Force -Recurse).Count -eq 1 -and -not(Test-Path -LiteralPath (Join-Path $temporary 'disabled-extra'))) 'Disabled backup called Docker or wrote backup/lock files'
    $enabledPrimary=$primary
    $enabledContainers=$global:opsTest.containers
    $primary=$disabled
    $global:opsTest.containers=@($enabledContainers|Where-Object {$_.Service -ne 'backup'})
    $result=Invoke-Check
    Assert-True ($result.passed -and $global:opsTest.checkCode -eq 0 -and $result.services.Count -eq 5 -and $result.backup.status -eq 'disabled' -and $result.backup.artifact.status -eq 'disabled') 'Disabled backup still required a backup container or SQL file'
    Assert-True ($null -eq $result.backup.lastSuccessAt -and $null -eq $result.backup.ageSeconds -and -not $result.backup.extraCopyVerified -and -not $result.backup.artifact.checksumVerified -and $result.backup.artifact.sizeBytes -eq 0) 'Disabled backup claimed a valid backup or success time'
    Assert-True (@(Get-ChildItem -LiteralPath $disabled -Force -Recurse).Count -eq 1) 'Disabled operations check wrote backup files'
    $primary=$enabledPrimary
    # A missing optional container never substitutes for an enabled, verified SQL backup.
    $result=Invoke-Check
    Assert-True (-not $result.passed -and $global:opsTest.checkCode -ne 0 -and $result.services.Count -eq 5 -and $result.issues -contains 'backup_status_unavailable' -and $result.issues -notcontains 'service_unhealthy:backup' -and -not $result.backup.artifact.checksumVerified) 'Missing optional container hid unavailable enabled backups'
    $global:opsTest.containers=$enabledContainers
    # Stage the actual scripts under a different root with its own disabled
    # default directory. An explicitly selected, enabled output must still run.
    $policyRoot=Join-Path $temporary 'policy-root'
    $policyScripts=Join-Path $policyRoot 'scripts'
    New-Item -ItemType Directory -Path $policyScripts,(Join-Path $policyRoot 'backups')|Out-Null
    [IO.File]::WriteAllText((Join-Path $policyRoot 'backups/.backup-disabled'),'')
    foreach($name in @('backup-db.ps1','backup-process.ps1')){[IO.File]::Copy((Join-Path $PSScriptRoot $name),(Join-Path $policyScripts $name))}
    & (Join-Path $policyScripts 'backup-db.ps1') -ProcessRunner $backupRunner -OutputDirectory $primary -EnvPath $fakeEnv
    $status=Get-Content -LiteralPath (Join-Path $primary 'backup-status.json') -Raw|ConvertFrom-Json
    Assert-True ($status.status -eq 'ok' -and $status.extraCopyVerified) 'Configured extra copy did not succeed'
    $primaryFile=Join-Path $primary $status.backupFile
    $extraFile=Join-Path $extra $status.backupFile
    Assert-True ((Get-FileHash -LiteralPath $primaryFile).Hash -eq (Get-FileHash -LiteralPath $extraFile).Hash) 'Extra backup content differs'
    Assert-True (([IO.File]::ReadAllText($extraFile+'.sha256').Trim()) -eq $status.sha256) 'Extra checksum missing'
    Assert-True ([IO.File]::ReadAllText((Join-Path $primary 'health/backup.prom')) -match '(?m)^ai_order_backup_last_attempt_success 1$') 'Success metric missing'
    $result=Invoke-Check
    Assert-True ($result.passed -and $global:opsTest.checkCode -eq 0 -and $result.services.Count -eq 6 -and $result.backup.artifact.checksumVerified) 'Healthy operations rejected'
    Assert-True ($result.mode -eq 'local' -and $result.public.status -eq 'not_checked' -and $null -eq $result.public.passed -and $global:opsTest.webCalls.Count -eq 0) 'Local check claimed public acceptance or used the network'
    $global:opsTest.containers=@($enabledContainers|Where-Object {$_.Service -ne 'backup'})
    $result=Invoke-Check
    Assert-True ($result.passed -and $result.services.Count -eq 5 -and $result.backup.artifact.checksumVerified) 'Optional absent backup container rejected a verified enabled backup'
    $global:opsTest.containers=$enabledContainers
    $global:opsTest.containers[4].Health='unhealthy'
    $result=Invoke-Check
    Assert-True (-not $result.passed -and $global:opsTest.checkCode -ne 0 -and $result.issues -contains 'service_unhealthy:backup') 'Unhealthy backup accepted despite a valid latest file'
    $global:opsTest.containers[4].Health='healthy'
    # Only mutate the disposable backup fixture; actual content must agree with both hashes.
    $goodDump=[IO.File]::ReadAllText($primaryFile)
    [IO.File]::WriteAllText($primaryFile,'tampered')
    $result=Invoke-Check
    Assert-True (-not $result.passed -and $global:opsTest.checkCode -ne 0 -and $result.issues -contains 'backup_checksum_mismatch') 'Tampered SQL accepted'
    [IO.File]::WriteAllText($primaryFile,'')
    $result=Invoke-Check
    Assert-True ($result.issues -contains 'backup_artifact_empty') 'Empty SQL accepted'
    [IO.File]::WriteAllText($primaryFile,$goodDump)
    $goodSidecar=[IO.File]::ReadAllText($primaryFile+'.sha256')
    [IO.File]::WriteAllText($primaryFile+'.sha256',('0'*64))
    $result=Invoke-Check
    Assert-True ($result.issues -contains 'backup_checksum_mismatch') 'Mismatched sidecar accepted'
    Remove-BackupPath ($primaryFile+'.sha256')
    $result=Invoke-Check
    Assert-True ($result.issues -contains 'backup_artifact_missing') 'Missing sidecar accepted'
    [IO.File]::WriteAllText($primaryFile+'.sha256',$goodSidecar)
    $statePath=Join-Path $primary 'backup-status.json'
    $goodState=[IO.File]::ReadAllText($statePath)
    $missing=$goodState|ConvertFrom-Json
    $missing.backupFile='orders-missing.sql'
    [IO.File]::WriteAllText($statePath,($missing|ConvertTo-Json -Compress))
    $result=Invoke-Check
    Assert-True ($result.issues -contains 'backup_artifact_missing') 'Missing SQL accepted'
    $wrongState=$goodState|ConvertFrom-Json
    $wrongState.sha256='0'*64
    [IO.File]::WriteAllText($statePath,($wrongState|ConvertTo-Json -Compress))
    $result=Invoke-Check
    Assert-True ($result.issues -contains 'backup_checksum_mismatch') 'Mismatched status checksum accepted'
    foreach($unsafeName in @('../outside.sql','..\outside.sql','/outside.sql','C:\outside.sql','orders.sql:stream')){
        $unsafe=$goodState|ConvertFrom-Json
        $unsafe.backupFile=$unsafeName
        [IO.File]::WriteAllText($statePath,($unsafe|ConvertTo-Json -Compress))
        $result=Invoke-Check
        Assert-True ($result.issues -contains 'backup_artifact_unsafe') 'Unsafe backup filename accepted'
    }
    [IO.File]::WriteAllText($statePath,$goodState)
    $reparse=Join-Path $temporary 'linked-primary'
    $originalPrimary=$primary
    try {
        $linkType=if([Environment]::OSVersion.Platform -eq [PlatformID]::Unix){'SymbolicLink'}else{'Junction'}
        New-Item -ItemType $linkType -Path $reparse -Target $originalPrimary|Out-Null
        $primary=$reparse
        $result=Invoke-Check
        Assert-True (-not $result.passed -and $result.issues -contains 'backup_status_unavailable') 'Reparse backup root accepted'
    } finally {
        $primary=$originalPrimary
        if(Test-Path -LiteralPath $reparse){Remove-BackupPath $reparse -Directory}
    }
    # No network is used: HTTPS and timeout behavior is tested through the request boundary.
    $global:opsTest.containers+=@(@{Service='proxy';State='running';Health=''})
    $result=Invoke-Check -PublicBaseUrl 'https://operations.invalid/'
    Assert-True ($result.passed -and $result.mode -eq 'public' -and $result.public.passed -and $result.public.tlsVerified -and $result.services.Count -eq 7) 'Public probe success contract failed'
    foreach($failureMode in @('web-failed','web-timeout','page-failed','not-ready')){
        $global:opsTest.mode=$failureMode
        $result=Invoke-Check -PublicBaseUrl 'https://operations.invalid/'
        Assert-True (-not $result.passed -and $global:opsTest.checkCode -ne 0 -and -not $result.public.passed -and -not $result.public.tlsVerified) 'Public probe failure accepted'
    }
    $global:opsTest.mode='success'
    $global:opsTest.containers=@($global:opsTest.containers|Where-Object {$_.Service -ne 'proxy'})
    $result=Invoke-Check -PublicBaseUrl 'https://operations.invalid/'
    Assert-True ($result.issues -contains 'service_unhealthy:proxy' -and -not $result.public.passed) 'Public mode accepted missing proxy'
    foreach($invalidUrl in @('http://operations.invalid/','https://user:secret@operations.invalid/','https://operations.invalid/?token=secret','https://operations.invalid/other')){
        $callCount=$global:opsTest.webCalls.Count
        $result=Invoke-Check -PublicBaseUrl $invalidUrl
        Assert-True ($result.issues -contains 'public_url_invalid' -and $global:opsTest.webCalls.Count -eq $callCount) 'Invalid public origin requested'
    }
    Assert-True (@(Get-ChildItem -LiteralPath $primary -Filter '*report*').Count -eq 0) 'Check wrote an implicit report'
    $newReportHash=if(Test-Path -LiteralPath $defaultReport){(Get-FileHash -LiteralPath $defaultReport).Hash}else{$null}
    Assert-True ($newReportHash -eq $oldReportHash) 'Check changed an existing default report'
    $lockDirectory=Join-Path $primary '.backup-lock'
    New-Item -ItemType Directory -Path $lockDirectory|Out-Null
    $oldState=[IO.File]::ReadAllText((Join-Path $primary 'backup-status.json'))
    $failed=$false
    try {Invoke-FixtureBackup -OutputDirectory $primary -EnvPath $fakeEnv}catch{$failed=$true}
    Assert-True ($failed -and [IO.File]::ReadAllText((Join-Path $primary 'backup-status.json')) -eq $oldState) 'Overlapping backup changed active state'
    Remove-BackupPath $lockDirectory -Directory
    $successfulAt=$status.lastSuccessAt
    $global:opsTest.mode='dump-failed'
    $failed=$false
    try {Invoke-FixtureBackup -OutputDirectory $primary -EnvPath $fakeEnv}catch{$failed=$true}
    Assert-True $failed 'Dump failure returned success'
    $status=Get-Content -LiteralPath (Join-Path $primary 'backup-status.json') -Raw|ConvertFrom-Json
    Assert-True ($status.status -eq 'failed' -and $status.lastSuccessAt -eq $successfulAt) 'Failure advanced last success'
    Assert-True ([IO.File]::ReadAllText((Join-Path $primary 'health/backup.prom')) -match '(?m)^ai_order_backup_last_attempt_success 0$') 'Failure metric missing'
    $global:opsTest.mode='success'
    $result=Invoke-Check
    Assert-True (-not $result.passed -and $global:opsTest.checkCode -ne 0 -and $result.issues -contains 'backup_last_attempt_failed') 'Recent failed attempt ignored'
    $blocked=Join-Path $temporary 'blocked'
    [IO.File]::WriteAllText($blocked,'not a directory')
    $failed=$false
    try {Invoke-FixtureBackup -OutputDirectory $primary -EnvPath $fakeEnv -ExtraBackupDirectory $blocked}catch{$failed=$true}
    Assert-True $failed 'Unwritable extra directory returned success'
    $status=Get-Content -LiteralPath (Join-Path $primary 'backup-status.json') -Raw|ConvertFrom-Json
    Assert-True ($status.status -eq 'failed' -and $status.lastSuccessAt -eq $successfulAt) 'Extra failure advanced last success'
    # Seed only temporary backups to verify seven-file retention and preservation.
    1..8|ForEach-Object {
        $path=Join-Path $primary ('orders-fixture-'+$_+'.sql')
        [IO.File]::WriteAllText($path,'fixture-'+$_)
        [IO.File]::WriteAllText(($path+'.sha256'),(Get-FileHash -LiteralPath $path).Hash)
        (Get-Item -LiteralPath $path).LastWriteTime=[DateTime]::UtcNow.AddDays(-20+$_)
    }
    $unverified=Join-Path $primary 'orders-unverified.sql'
    [IO.File]::WriteAllText($unverified,'preserve me')
    (Get-Item -LiteralPath $unverified).LastWriteTime=[DateTime]::UtcNow.AddDays(-30)
    $global:opsTest.mode='extra-failed'
    1..3|ForEach-Object {
        $failed=$false
        try {Invoke-FixtureBackup -OutputDirectory $primary -EnvPath $fakeEnv}catch{$failed=$true}
        Assert-True $failed 'Repeated extra failure returned success'
        Assert-True (Test-Path -LiteralPath $unverified) 'Unverified backup deleted'
        Assert-True (@(Get-ChildItem -LiteralPath $primary -Filter 'orders-*.sql').Count -eq 8) 'Extra copy failure accumulated primary backups beyond seven newest plus unverified old file'
    }
    $global:opsTest.mode='success'
    Invoke-FixtureBackup -OutputDirectory $primary -EnvPath $fakeEnv -ExtraBackupDirectory ''
    $status=Get-Content -LiteralPath (Join-Path $primary 'backup-status.json') -Raw|ConvertFrom-Json
    $status.lastSuccessAt=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()-31*3600
    [IO.File]::WriteAllText((Join-Path $primary 'backup-status.json'),($status|ConvertTo-Json -Compress))
    $reportPath=Join-Path $temporary 'reported/check.json'
    $result=Invoke-Check -ReportPath $reportPath
    Assert-True (-not $result.passed -and $global:opsTest.checkCode -ne 0 -and $result.issues -contains 'backup_stale' -and (Test-Path -LiteralPath $reportPath)) 'Stale backup/report contract failed'
    $global:opsTest.containers[0].Health='unhealthy'
    $result=Invoke-Check
    Assert-True ($global:opsTest.checkCode -ne 0 -and $result.issues -contains 'service_unhealthy:mysql') 'Unhealthy database ignored'
    $global:opsTest.mode='docker-failed'
    $result=Invoke-Check
    Assert-True ($global:opsTest.checkCode -ne 0 -and $result.issues -contains 'container_check_failed') 'Docker failure ignored'
    Remove-BackupPath (Join-Path $primary 'backup-status.json')
    $result=Invoke-Check
    Assert-True ($result.issues -contains 'backup_status_unavailable') 'Missing status ignored'
    Assert-True (@($global:opsTest.dockerCalls|Where-Object {$_ -match '\b(up|stop|restart|down|run)\b'}).Count -eq 0) 'Operations check mutated containers'
    Write-Output 'PASS operations: disabled/optional backups, integrity/path safety, local/public probes, failed attempts, retention, freshness, container health and JSON exit contract (mock Docker/HTTPS only)'
} finally {
    Remove-Item Function:\docker -ErrorAction SilentlyContinue
    Remove-Item Function:\Copy-Item -ErrorAction SilentlyContinue
    Remove-Item Function:\Invoke-WebRequest -ErrorAction SilentlyContinue
    $resolved=[IO.Path]::GetFullPath($temporary)
    $base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if(-not $resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notmatch '^ai-order-ops-test-[a-f0-9]{32}$'){throw 'Unsafe temporary cleanup target'}
    Remove-BackupPath $resolved -Directory
}
