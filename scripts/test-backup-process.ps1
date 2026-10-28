# Isolated process tests: no real Docker, database or existing backup paths.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'backup-process.ps1')
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('ai-order-backup-process-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary|Out-Null
$engine=(Get-Process -Id $PID).Path
$worker=Join-Path $temporary 'hanging-worker.ps1'
$trace=Join-Path $temporary 'heartbeat'
$childTrace=Join-Path $temporary 'child-heartbeat'
$childPidFile=Join-Path $temporary 'orphan-child-pid'
$workerSource=@'
param([string]$Trace,[string]$ChildTrace,[string]$PidPath,[switch]$Child,[switch]$ExitParent)
if(-not $Child){
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=(Get-Process -Id $PID).Path
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $values=@('-NoProfile','-NonInteractive','-File',$PSCommandPath,'-Trace',$ChildTrace,'-PidPath',$PidPath,'-Child')
    if($start.PSObject.Properties.Name -contains 'ArgumentList'){
        foreach($value in $values){$start.ArgumentList.Add($value)}
    }else{$start.Arguments=($values|ForEach-Object {'"'+$_+'"'}) -join ' '}
    $null=[Diagnostics.Process]::Start($start)
    if($ExitParent){[IO.File]::WriteAllText(($PidPath+'.parent-exited'),'exiting');exit 0}
}
if($Child -and $PidPath){[IO.File]::WriteAllText($PidPath,[string]$PID);[Console]::WriteLine('fixture descendant holds inherited output')}
while($true){[IO.File]::AppendAllText($Trace,'x');Start-Sleep -Milliseconds 40}
'@
[IO.File]::WriteAllText($worker,$workerSource,[Text.UTF8Encoding]::new($false))
function Assert-True($Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Assert-WorkerStopped {
    Assert-True (Test-Path -LiteralPath $trace) 'Hanging worker never started'
    Assert-True (Test-Path -LiteralPath $childTrace) 'Hanging descendant never started'
    $before=[IO.File]::ReadAllText($trace)
    $childBefore=[IO.File]::ReadAllText($childTrace)
    Start-Sleep -Milliseconds 300
    Assert-True ([IO.File]::ReadAllText($trace) -eq $before -and [IO.File]::ReadAllText($childTrace) -eq $childBefore) 'Worker or descendant wrote after timeout returned'
}
$primary=Join-Path $temporary 'primary'
$extra=Join-Path $temporary 'extra'
$envFile=Join-Path $temporary 'fixture.env'
[IO.File]::WriteAllText($envFile,'')
$global:backupProcessMode='success'
$global:backupCleanupCalls=0
$global:backupProcessPosix=$false
$runner={
    param($Executable,$Arguments,$Seconds)
    if($Executable -ne 'docker'){
        if($global:backupProcessMode -eq 'orphan-output'){
            return Invoke-BoundedBackupProcess $engine @('-NoProfile','-NonInteractive','-File',$worker,'-Trace',$trace,'-ChildTrace',$childTrace,'-PidPath',$childPidFile,'-ExitParent') 8
        }
        if($global:backupProcessMode -eq 'copy-hang'){
            return Invoke-BoundedBackupProcess $engine @('-NoProfile','-NonInteractive','-File',$worker,'-Trace',$trace,'-ChildTrace',$childTrace) 8
        }
        return Invoke-BoundedBackupProcess $Executable $Arguments $Seconds
    }
    if($Arguments -contains 'ps'){return [pscustomobject]@{ExitCode=0;Output='fixture-mysql';Error=''}}
    if($Arguments[0] -eq 'cp'){
        if($global:backupProcessPosix){Copy-Item -LiteralPath ($Arguments[1].Substring($Arguments[1].IndexOf(':')+1)) -Destination $Arguments[2]}
        else{[IO.File]::WriteAllText($Arguments[2],('-- fixture '+('x'*150)+"`n-- Dump completed`n"))}
    }elseif($Arguments[-1] -match 'mysqldump'){
        Assert-True ($Arguments[-1] -match 'timeout --kill-after=5 1 sh -c') 'Dump was not supervised inside the container'
        if($global:backupProcessPosix){return Invoke-BoundedBackupProcess '/bin/sh' @('-c',$Arguments[-1]) $Seconds}
        if($global:backupProcessMode -eq 'dump-hang'){
            $null=Invoke-BoundedBackupProcess $engine @('-NoProfile','-NonInteractive','-File',$worker,'-Trace',$trace,'-ChildTrace',$childTrace) 8
        }
        if($global:backupProcessMode -eq 'uncertain'){throw 'Fixture docker transport lost'}
    }elseif($Arguments[-1] -match 'kill -TERM'){
        $global:backupCleanupCalls++
        if($global:backupProcessPosix){return Invoke-BoundedBackupProcess '/usr/bin/timeout' @('--kill-after=5','15','sh','-c',$Arguments[-1]) $Seconds}
        if($global:backupProcessMode -eq 'uncertain'){throw 'Fixture remote termination unavailable'}
    }else{throw 'Unexpected backup Docker call'}
    return [pscustomobject]@{ExitCode=0;Output='';Error=''}
}
function Invoke-FixtureBackup {
    & (Join-Path $PSScriptRoot 'backup-db.ps1') -OutputDirectory $primary -ExtraBackupDirectory $extra -EnvPath $envFile -DumpTimeoutSeconds 1 -CopyTimeoutSeconds 10 -ProcessRunner $runner
}
try {
    Invoke-FixtureBackup
    $statusPath=Join-Path $primary 'backup-status.json'
    $good=[IO.File]::ReadAllText($statusPath)|ConvertFrom-Json
    foreach($failure in @('dump-hang','copy-hang')){
        $global:backupProcessMode=$failure
        $failed=$false
        $clock=[Diagnostics.Stopwatch]::StartNew()
        try {Invoke-FixtureBackup}catch{$failed=$true}
        Assert-True ($failed -and $clock.Elapsed.TotalSeconds -lt 25) 'Hanging backup phase did not fail within its deadline'
        Assert-WorkerStopped
        $state=[IO.File]::ReadAllText($statusPath)|ConvertFrom-Json
        Assert-True ($state.status -eq 'failed' -and $state.lastSuccessAt -eq $good.lastSuccessAt) 'Timeout advanced the last successful backup'
        Assert-True (Test-Path -LiteralPath (Join-Path $primary $good.backupFile)) 'Timeout lost the last valid backup'
        Assert-True (-not(Test-Path -LiteralPath (Join-Path $primary '.backup-lock'))) 'Stopped worker did not release its owned lock'
        Assert-True (@(Get-ChildItem -LiteralPath $primary,$extra -Filter '*.tmp').Count -eq 0) 'Timeout left a writable partial file'
        $global:backupProcessMode='success'
        Invoke-FixtureBackup
        $good=[IO.File]::ReadAllText($statusPath)|ConvertFrom-Json
    }
    Assert-True ($global:backupCleanupCalls -ge 4) 'Remote termination/cleanup was not verified'
    # The parent exits successfully, while a descendant retains its redirected
    # output handles. A completed parent wait must not turn into an infinite read
    # or claim that this unknown descendant has stopped.
    $global:backupProcessMode='orphan-output'
    $failure=$null
    $clock=[Diagnostics.Stopwatch]::StartNew()
    try {
        try {Invoke-FixtureBackup}catch{$failure=$_}
        Assert-True ($null -ne $failure -and $failure.Exception.Data['BackupWorkerMayBeRunning'] -and $clock.Elapsed.TotalSeconds -lt 25) 'Inherited output was not bounded or incorrectly claimed descendant termination'
        Assert-True ((Test-Path -LiteralPath ($childPidFile+'.parent-exited')) -and (Test-Path -LiteralPath $childPidFile)) 'Parent-exit/descendant fixture did not run'
        Assert-True (Test-Path -LiteralPath (Join-Path $primary '.backup-lock')) 'Unknown inherited-output descendant incorrectly released the lock'
    }finally{
        if(Test-Path -LiteralPath $childPidFile){
            $ownedChild=[Diagnostics.Process]::GetProcessById([int][IO.File]::ReadAllText($childPidFile))
            try {
                if(-not $ownedChild.HasExited){
                    if([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT){
                        $stop=[Diagnostics.ProcessStartInfo]::new()
                        $stop.FileName=Join-Path $env:SystemRoot 'System32/taskkill.exe'
                        $stop.Arguments='/PID '+$ownedChild.Id+' /T /F'
                        $stop.UseShellExecute=$false;$stop.CreateNoWindow=$true
                        $stop.RedirectStandardOutput=$true;$stop.RedirectStandardError=$true
                        $kill=[Diagnostics.Process]::Start($stop)
                        try {$kill.WaitForExit();Assert-True ($kill.ExitCode -eq 0) 'Fixture descendant cleanup failed'}finally{$kill.Dispose()}
                    }else{$ownedChild.Kill($true)}
                    Assert-True ($ownedChild.WaitForExit(10000)) 'Fixture descendant did not stop'
                }
            }finally{$ownedChild.Dispose()}
        }
    }
    Remove-BackupPath (Join-Path $primary '.backup-lock') -Directory
    $global:backupProcessMode='success'
    if([Environment]::OSVersion.Platform -eq [PlatformID]::Unix){
        # Execute the actual generated container shell command against a fake
        # mysqldump. This verifies its supervisor/PID cleanup without a database.
        $bin=Join-Path $temporary 'bin'
        New-Item -ItemType Directory -Path $bin|Out-Null
        $dump=Join-Path $bin 'mysqldump'
        $dumpSource=@'
#!/bin/sh
if [ "${FAKE_REMOTE_HANG:-}" = 1 ]; then
    trap '' TERM
    while :; do printf x >> "$FAKE_REMOTE_TRACE"; /usr/bin/sleep 0.05; done
fi
printf '%s\n' '-- fixture xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' '-- Dump completed'
'@
        [IO.File]::WriteAllText($dump,$dumpSource,[Text.UTF8Encoding]::new($false))
        [IO.File]::SetUnixFileMode($dump,[IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute)
        $savedPath=$env:PATH
        try {
            $env:PATH=$bin+':'+$env:PATH
            $env:MYSQL_ROOT_PASSWORD='fixture-only'
            $env:FAKE_REMOTE_TRACE=Join-Path $temporary 'remote-heartbeat'
            $global:backupProcessPosix=$true
            $global:backupProcessMode='success'
            Invoke-FixtureBackup
            $good=[IO.File]::ReadAllText($statusPath)|ConvertFrom-Json
            $env:FAKE_REMOTE_HANG='1'
            $failed=$false
            try {Invoke-FixtureBackup}catch{$failed=$true}
            Assert-True $failed 'Container-side hanging dump returned success'
            $before=[IO.File]::ReadAllText($env:FAKE_REMOTE_TRACE)
            Start-Sleep -Milliseconds 300
            Assert-True ([IO.File]::ReadAllText($env:FAKE_REMOTE_TRACE) -eq $before) 'Container-side dump outlived cleanup'
            $state=[IO.File]::ReadAllText($statusPath)|ConvertFrom-Json
            Assert-True ($state.status -eq 'failed' -and $state.lastSuccessAt -eq $good.lastSuccessAt -and -not(Test-Path -LiteralPath (Join-Path $primary '.backup-lock'))) 'Container timeout did not safely release its owned lock'
        }finally{$env:PATH=$savedPath;$env:FAKE_REMOTE_HANG=$null;$global:backupProcessPosix=$false}
    }
    $global:backupProcessMode='uncertain'
    $failed=$false
    try {Invoke-FixtureBackup}catch{$failed=$true}
    Assert-True ($failed -and (Test-Path -LiteralPath (Join-Path $primary '.backup-lock'))) 'Uncertain remote worker termination released the backup lock'
    Write-Output 'PASS backup process supervision: real hanging parent/descendant, dump/copy deadlines, failed state, recovery, and conservative remote lock retention'
}finally{
    $resolved=[IO.Path]::GetFullPath($temporary)
    $base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if(-not $resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notmatch '^ai-order-backup-process-[a-f0-9]{32}$'){throw 'Unsafe fixture cleanup path'}
    Remove-BackupPath $resolved -Directory
}
