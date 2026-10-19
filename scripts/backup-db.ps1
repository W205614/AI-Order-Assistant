param(
    [string]$ProjectName='ai-order-assistant',
    [string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),
    [string]$OutputDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) 'backups'),
    [string]$ExtraBackupDirectory=$env:BACKUP_EXTRA_DIRECTORY
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
$healthDirectory=Join-Path $OutputDirectory 'health'
New-Item -ItemType Directory -Force -Path $OutputDirectory,$healthDirectory | Out-Null
$lockDirectory=Join-Path $OutputDirectory '.backup-lock'
try {New-Item -ItemType Directory -Path $lockDirectory -ErrorAction Stop|Out-Null}
catch {throw 'Backup lock unavailable; another backup may be active. Inspect the backup directory before retrying.'}
$statusFile=Join-Path $OutputDirectory 'backup-status.json'
$lastSuccess=0L
if(Test-Path -LiteralPath $statusFile){
    try {$lastSuccess=[long](([IO.File]::ReadAllText($statusFile)|ConvertFrom-Json).lastSuccessAt)} catch {$lastSuccess=0L}
}
$previousSuccess=$lastSuccess
$attempt=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$file=Join-Path $OutputDirectory ('orders-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+$PID+'.sql')
$hash=$null
$extraVerified=$false
$copy=$null
$remote=$null

function Write-AtomicText([string]$Path,[string]$Value){
    $temporary=$Path+'.'+[guid]::NewGuid().ToString('N')+'.tmp'
    try {
        [IO.File]::WriteAllText($temporary,$Value,[Text.UTF8Encoding]::new($false))
        if([Environment]::OSVersion.Platform -eq [PlatformID]::Unix){
            $mode=[IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite
            if($Path -eq (Join-Path $healthDirectory 'backup.prom')){$mode=$mode -bor [IO.UnixFileMode]::GroupRead -bor [IO.UnixFileMode]::OtherRead}
            [IO.File]::SetUnixFileMode($temporary,$mode)
        }
        # Same-directory replace is atomic, including Windows PowerShell 5.1.
        if([IO.File]::Exists($Path)){[IO.File]::Replace($temporary,$Path,[NullString]::Value)}else{[IO.File]::Move($temporary,$Path)}
    } finally {
        if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary -Force}
    }
}

function Write-BackupState([string]$Status){
    $success=if($Status -eq 'ok'){1}else{0}
    $metrics="# TYPE ai_order_backup_last_success_timestamp_seconds gauge`nai_order_backup_last_success_timestamp_seconds $lastSuccess`n# TYPE ai_order_backup_last_attempt_success gauge`nai_order_backup_last_attempt_success $success`n"
    Write-AtomicText (Join-Path $healthDirectory 'backup.prom') $metrics
    $state=[ordered]@{schemaVersion=1;status=$Status;lastAttemptAt=$attempt;lastSuccessAt=$lastSuccess;backupFile=[IO.Path]::GetFileName($file);sha256=$hash;extraCopyVerified=$extraVerified}
    Write-AtomicText $statusFile ($state|ConvertTo-Json -Compress)
}

function Remove-VerifiedOldBackups([string]$Directory){
    # Delete only verified old dumps inside this dedicated directory; retain 7.
    $base=[IO.Path]::GetFullPath($Directory).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    $old=@(Get-ChildItem -LiteralPath $Directory -Filter 'orders-*.sql' | Sort-Object LastWriteTime -Descending | Select-Object -Skip 7)
    foreach($entry in $old){
        $target=[IO.Path]::GetFullPath($entry.FullName)
        if(-not $target.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'Backup target escaped directory'}
        if(-not(Test-Path -LiteralPath ($target+'.sha256')) -or (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne [IO.File]::ReadAllText($target+'.sha256').Trim()){
            Write-Warning 'An old unverified dump was preserved; inspect backups before removing it.'
            continue
        }
        Remove-Item -LiteralPath $target -Force
        Remove-Item -LiteralPath ($target+'.sha256') -Force
    }
}

try {
    if([Environment]::OSVersion.Platform -eq [PlatformID]::Unix){
        [IO.File]::SetUnixFileMode($healthDirectory,[IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute -bor [IO.UnixFileMode]::GroupRead -bor [IO.UnixFileMode]::GroupExecute -bor [IO.UnixFileMode]::OtherRead -bor [IO.UnixFileMode]::OtherExecute)
    }
    # Read only this non-secret setting; do not print or expand other .env values.
    if(-not $PSBoundParameters.ContainsKey('ExtraBackupDirectory') -and -not $ExtraBackupDirectory -and (Test-Path -LiteralPath $EnvPath)){
        foreach($line in [IO.File]::ReadLines([IO.Path]::GetFullPath($EnvPath))){
            if($line -match '^\s*BACKUP_EXTRA_DIRECTORY\s*=\s*(.*?)\s*$'){
                $ExtraBackupDirectory=$Matches[1].Trim().Trim('"').Trim("'")
            }
        }
    }
    if($ExtraBackupDirectory){
        if(-not [IO.Path]::IsPathRooted($ExtraBackupDirectory)){$ExtraBackupDirectory=Join-Path $root $ExtraBackupDirectory}
        $ExtraBackupDirectory=[IO.Path]::GetFullPath($ExtraBackupDirectory)
        if($ExtraBackupDirectory.TrimEnd('\','/') -eq $OutputDirectory.TrimEnd('\','/')){throw 'Extra backup directory must differ from the primary directory'}
        New-Item -ItemType Directory -Force -Path $ExtraBackupDirectory | Out-Null
    }
    $remote='/tmp/order-backup-'+[guid]::NewGuid().ToString('N')+'.sql'
    $dumpCommand='umask 077; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF ai_order_assistant > '+$remote
    & docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') exec -T mysql sh -c $dumpCommand
    if($LASTEXITCODE -ne 0){throw 'Database dump failed'}
    $container=& docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') ps -q mysql
    if($LASTEXITCODE -ne 0 -or -not $container){throw 'Database container lookup failed'}
    & docker cp "${container}:$remote" ($file+'.tmp')
    if($LASTEXITCODE -ne 0){throw 'Database dump copy failed'}
    & docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') exec -T mysql rm $remote
    if($LASTEXITCODE -ne 0){throw 'Database temporary dump cleanup failed'}
    $remote=$null
    if((Get-Item -LiteralPath ($file+'.tmp')).Length -lt 100 -or -not (Select-String -LiteralPath ($file+'.tmp') -Pattern 'Dump completed')){throw 'Dump verification failed'}
    Move-Item -LiteralPath ($file+'.tmp') -Destination $file
    $hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-AtomicText ($file+'.sha256') $hash
    # Retain valid local backups even while an enabled additional copy is failing.
    Remove-VerifiedOldBackups $OutputDirectory
    if($ExtraBackupDirectory){
        $copy=Join-Path $ExtraBackupDirectory ([IO.Path]::GetFileName($file))
        Copy-Item -LiteralPath $file -Destination ($copy+'.tmp')
        if((Get-FileHash -LiteralPath ($copy+'.tmp') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash){throw 'Extra backup checksum mismatch'}
        Move-Item -LiteralPath ($copy+'.tmp') -Destination $copy
        Write-AtomicText ($copy+'.sha256') $hash
        $extraVerified=$true
    }
    if($ExtraBackupDirectory){Remove-VerifiedOldBackups $ExtraBackupDirectory}
    $lastSuccess=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    Write-BackupState 'ok'
    Write-Host "Verified backup: $file"
} catch {
    $lastSuccess=$previousSuccess
    try {Write-BackupState 'failed'} catch {Write-Warning 'Backup status could not be persisted; inspect backup storage.'}
    throw
} finally {
    if(Test-Path -LiteralPath ($file+'.tmp')){Remove-Item -LiteralPath ($file+'.tmp') -Force}
    if($copy -and (Test-Path -LiteralPath ($copy+'.tmp'))){Remove-Item -LiteralPath ($copy+'.tmp') -Force}
    if($remote){
        & docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') exec -T mysql rm -f $remote
        if($LASTEXITCODE -ne 0){Write-Warning 'Database temporary backup cleanup failed; inspect the MySQL /tmp directory.'}
    }
    Remove-Item -LiteralPath $lockDirectory -Force
}
