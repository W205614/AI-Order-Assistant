param(
    [string]$ProjectName='ai-order-assistant',
    [string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),
    [string]$OutputDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) 'backups'),
    [string]$ExtraBackupDirectory=$env:BACKUP_EXTRA_DIRECTORY,
    [int]$DumpTimeoutSeconds=300,
    [int]$CopyTimeoutSeconds=120,
    [Parameter(DontShow)][scriptblock]$ProcessRunner
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath (Join-Path $OutputDirectory '.backup-disabled')){
    Write-Host 'Database backup disabled by local policy; no export or backup copy was started.'
    return
}
. (Join-Path $PSScriptRoot 'backup-process.ps1')
if(-not $ProcessRunner){$ProcessRunner={param($Executable,$Arguments,$Seconds) Invoke-BoundedBackupProcess $Executable $Arguments $Seconds}}
$safeSettings=@{}
if(Test-Path -LiteralPath $EnvPath){
    foreach($line in [IO.File]::ReadLines([IO.Path]::GetFullPath($EnvPath))){
        if($line -match '^\s*(BACKUP_(?:EXTRA_DIRECTORY|DUMP_TIMEOUT_SECONDS|COPY_TIMEOUT_SECONDS))\s*=\s*(.*?)\s*$'){$safeSettings[$Matches[1]]=$Matches[2].Trim().Trim('"').Trim("'")}
    }
}
foreach($setting in @(@('DumpTimeoutSeconds','BACKUP_DUMP_TIMEOUT_SECONDS',3600),@('CopyTimeoutSeconds','BACKUP_COPY_TIMEOUT_SECONDS',1800))){
    $raw=Get-Variable -Name $setting[0] -ValueOnly
    if(-not $PSBoundParameters.ContainsKey($setting[0])){
        $configured=[Environment]::GetEnvironmentVariable($setting[1])
        if(-not $configured -and $safeSettings.ContainsKey($setting[1])){$configured=$safeSettings[$setting[1]]}
        if($configured){$raw=$configured}
    }
    $parsed=0
    if(-not [int]::TryParse([string]$raw,[ref]$parsed) -or $parsed -lt 1 -or $parsed -gt $setting[2]){throw ($setting[1]+' is outside its supported positive timeout range')}
    Set-Variable -Name $setting[0] -Value $parsed
}
$healthDirectory=Join-Path $OutputDirectory 'health'
New-Item -ItemType Directory -Force -Path $OutputDirectory,$healthDirectory | Out-Null
$lockDirectory=Join-Path $OutputDirectory '.backup-lock'
try {New-Item -ItemType Directory -Path $lockDirectory -ErrorAction Stop|Out-Null}
catch {throw 'Backup lock unavailable; another backup may be active. Inspect the backup directory before retrying.'}
$lockOwner=[guid]::NewGuid().ToString('N')
$lockOwnerPath=Join-Path $lockDirectory 'owner'
[IO.File]::WriteAllText($lockOwnerPath,$lockOwner)
$releaseLock=$true
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
$container=$null
# The container must provide GNU process-group supervision, just like backup.sh.
# Ubuntu's uutils-compatible CLI can kill the supervised shell while leaving a
# TERM-resistant dump descendant alive; do not infer termination from that PID.
$gnuTimeoutSelector='timeout_command=""; for candidate in timeout gnutimeout; do resolved=$(command -v "$candidate" 2>/dev/null || :); if [ -n "$resolved" ] && "$resolved" --version 2>/dev/null | grep -q ''^timeout (GNU coreutils) ''; then timeout_command="$resolved"; break; fi; done; if [ -z "$timeout_command" ]; then printf "GNU timeout is required for backup process-group supervision\n" >&2; exit 1; fi; '

function Invoke-BackupDocker([string[]]$DockerArguments,[int]$Seconds){
    $result=& $ProcessRunner 'docker' $DockerArguments $Seconds
    if($result.ExitCode -ne 0){throw 'Docker backup operation failed'}
    return $result.Output
}

function Clear-RemoteBackup {
    if(-not $remote){return}
    # The timeout supervisor contains this unique path in argv. Never signal an
    # unrelated PID if the original process has already exited and its PID reused.
    $cleanup='set -eu; d='+$remote+'; [ -s "$d/pid" ] || exit 1; if [ -s "$d/pid" ]; then p=$(cat "$d/pid"); if kill -0 "$p" 2>/dev/null; then tr "\000" " " < "/proc/$p/cmdline" | grep -F -- "$d" >/dev/null || exit 1; kill -TERM "$p"; n=0; while kill -0 "$p" 2>/dev/null; do n=$((n+1)); [ "$n" -le 10 ] || exit 1; sleep 1; done; fi; fi; rm -rf -- "$d"'
    $launcher='set -eu; '+$gnuTimeoutSelector+'exec "$timeout_command" --kill-after=5 15 sh -c "$1"'
    $null=Invoke-BackupDocker ($composeArgs+@('exec','-T','mysql','sh','-c',$launcher,'backup-cleanup',$cleanup)) 25
}

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
        if(Test-Path -LiteralPath $temporary){Remove-BackupPath $temporary}
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
        Remove-BackupPath $target
        Remove-BackupPath ($target+'.sha256')
    }
}

try {
    if([Environment]::OSVersion.Platform -eq [PlatformID]::Unix){
        [IO.File]::SetUnixFileMode($healthDirectory,[IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute -bor [IO.UnixFileMode]::GroupRead -bor [IO.UnixFileMode]::GroupExecute -bor [IO.UnixFileMode]::OtherRead -bor [IO.UnixFileMode]::OtherExecute)
    }
    # Read only this non-secret setting; do not print or expand other .env values.
    if(-not $PSBoundParameters.ContainsKey('ExtraBackupDirectory') -and -not $ExtraBackupDirectory -and $safeSettings.ContainsKey('BACKUP_EXTRA_DIRECTORY')){
        $ExtraBackupDirectory=$safeSettings['BACKUP_EXTRA_DIRECTORY']
    }
    if($ExtraBackupDirectory){
        if(-not [IO.Path]::IsPathRooted($ExtraBackupDirectory)){$ExtraBackupDirectory=Join-Path $root $ExtraBackupDirectory}
        $ExtraBackupDirectory=[IO.Path]::GetFullPath($ExtraBackupDirectory)
        if($ExtraBackupDirectory.TrimEnd('\','/') -eq $OutputDirectory.TrimEnd('\','/')){throw 'Extra backup directory must differ from the primary directory'}
    }
    $composeArgs=@('compose','--env-file',$EnvPath,'-p',$ProjectName,'-f',(Join-Path $root 'docker-compose.yml'))
    $remote='/tmp/order-backup-'+[guid]::NewGuid().ToString('N')
    $dumpCommand='umask 077; set -eu; '+$gnuTimeoutSelector+'mkdir '+$remote+'; export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; "$timeout_command" --kill-after=5 '+$DumpTimeoutSeconds+' sh -c ''trap "" TERM; mysqldump -uroot --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF ai_order_assistant > "$1/dump.sql"'' backup-dump '+$remote+' & p=$!; printf "%s\n" "$p" > '+$remote+'/pid; result=0; wait "$p" || result=$?; exit "$result"'
    $null=Invoke-BackupDocker ($composeArgs+@('exec','-T','mysql','sh','-c',$dumpCommand)) ($DumpTimeoutSeconds+30)
    $container=(Invoke-BackupDocker ($composeArgs+@('ps','-q','mysql')) 20).Trim()
    if(-not $container){throw 'Database container lookup failed'}
    $null=Invoke-BackupDocker @('cp',"${container}:$remote/dump.sql",($file+'.tmp')) $CopyTimeoutSeconds
    Clear-RemoteBackup
    $remote=$null
    if((Get-Item -LiteralPath ($file+'.tmp')).Length -lt 100 -or -not (Select-String -LiteralPath ($file+'.tmp') -Pattern 'Dump completed')){throw 'Dump verification failed'}
    Move-Item -LiteralPath ($file+'.tmp') -Destination $file
    $hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-AtomicText ($file+'.sha256') $hash
    # Retain valid local backups even while an enabled additional copy is failing.
    Remove-VerifiedOldBackups $OutputDirectory
    if($ExtraBackupDirectory){
        $copy=Join-Path $ExtraBackupDirectory ([IO.Path]::GetFileName($file))
        $engine=(Get-Process -Id $PID).Path
        $result=& $ProcessRunner $engine @('-NoProfile','-NonInteractive','-File',(Join-Path $PSScriptRoot 'backup-copy-worker.ps1'),'-Source',$file,'-Destination',$copy,'-ExpectedHash',$hash) $CopyTimeoutSeconds
        if($result.ExitCode -ne 0){throw 'Extra backup copy failed'}
        $extraVerified=$true
    }
    if($ExtraBackupDirectory){Remove-VerifiedOldBackups $ExtraBackupDirectory}
    $lastSuccess=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    Write-BackupState 'ok'
    Write-Host "Verified backup: $file"
} catch {
    if($_.Exception.Data['BackupWorkerMayBeRunning']){$releaseLock=$false}
    $lastSuccess=$previousSuccess
    try {Write-BackupState 'failed'} catch {Write-Warning 'Backup status could not be persisted; inspect backup storage.'}
    throw
} finally {
    if($remote){
        try {Clear-RemoteBackup}catch{$releaseLock=$false;Write-Warning 'Remote worker termination could not be confirmed; backup lock retained. Inspect the MySQL backup worker before removing this lock.'}
    }
    if($releaseLock){
        if(Test-Path -LiteralPath ($file+'.tmp')){Remove-BackupPath ($file+'.tmp')}
        if($copy -and (Test-Path -LiteralPath ($copy+'.tmp'))){Remove-BackupPath ($copy+'.tmp')}
        if($copy -and (Test-Path -LiteralPath ($copy+'.sha256.tmp'))){Remove-BackupPath ($copy+'.sha256.tmp')}
        if((Test-Path -LiteralPath $lockOwnerPath) -and [IO.File]::ReadAllText($lockOwnerPath) -eq $lockOwner){Remove-BackupPath $lockDirectory -Directory}
        else{Write-Warning 'Backup lock ownership changed; lock preserved'}
    }
}
