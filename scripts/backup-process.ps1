function Invoke-BoundedBackupProcess {
    param([string]$Executable,[string[]]$Arguments,[int]$TimeoutSeconds)
    $command=Get-Command $Executable -CommandType Application -ErrorAction Stop|Select-Object -First 1
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$command.Source
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true
    $start.RedirectStandardError=$true
    if($start.PSObject.Properties.Name -contains 'ArgumentList'){
        foreach($value in $Arguments){$start.ArgumentList.Add($value)}
    }else{
        # CommandLineToArgvW quoting for Windows PowerShell 5.1.
        $start.Arguments=($Arguments|ForEach-Object {'"'+([regex]::Replace([regex]::Replace($_,'(\\*)"','$1$1\"'),'(\\+)$','$1$1'))+'"'}) -join ' '
    }
    $process=[Diagnostics.Process]::new()
    $process.StartInfo=$start
    $terminationUnconfirmed=$false
    try {
        if(-not $process.Start()){throw 'Backup worker could not start'}
        $clock=[Diagnostics.Stopwatch]::StartNew()
        $stdout=$process.StandardOutput.ReadToEndAsync()
        $stderr=$process.StandardError.ReadToEndAsync()
        $expired=-not $process.WaitForExit($TimeoutSeconds*1000)
        if($expired){
            if([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT){
                # Kill the complete native child tree, including a copy worker's children.
                $killer=[Diagnostics.ProcessStartInfo]::new()
                $killer.FileName=Join-Path $env:SystemRoot 'System32/taskkill.exe'
                $killer.Arguments='/PID '+$process.Id+' /T /F'
                $killer.UseShellExecute=$false
                $killer.CreateNoWindow=$true
                $killer.RedirectStandardOutput=$true
                $killer.RedirectStandardError=$true
                $termination=[Diagnostics.Process]::Start($killer)
                try {
                    if(-not $termination.WaitForExit(10000)){
                        $terminationUnconfirmed=$true
                        $termination.Kill()
                        throw 'Backup process-tree termination did not finish'
                    }
                    if($termination.ExitCode -ne 0){
                        $terminationUnconfirmed=$true
                        throw 'Backup process-tree termination failed; descendant termination is unconfirmed'
                    }
                }finally{$termination.Dispose()}
            }else{$process.Kill($true)}
            if(-not $process.WaitForExit(10000)){
                $terminationUnconfirmed=$true
                throw 'Backup worker termination could not be confirmed'
            }
            if(-not [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($stdout,$stderr),5000)){
                $terminationUnconfirmed=$true
                throw 'Backup worker output is still held by an unconfirmed descendant'
            }
            throw [TimeoutException]::new("Backup worker exceeded $TimeoutSeconds seconds; process tree terminated and reaped")
        }
        # A parent can exit while a descendant keeps its inherited output pipe
        # open. Reading output must share the original deadline, not block after
        # the timed parent wait has already succeeded.
        $remaining=[int][Math]::Max(0,$TimeoutSeconds*1000-$clock.ElapsedMilliseconds)
        if(-not [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($stdout,$stderr),$remaining)){
            $terminationUnconfirmed=$true
            throw [TimeoutException]::new('Backup worker output deadline expired; descendant termination is unconfirmed')
        }
        return [pscustomobject]@{ExitCode=$process.ExitCode;Output=$stdout.GetAwaiter().GetResult();Error=$stderr.GetAwaiter().GetResult()}
    } catch {
        if($terminationUnconfirmed){$_.Exception.Data['BackupWorkerMayBeRunning']=$true}
        try {if(-not $process.HasExited){$_.Exception.Data['BackupWorkerMayBeRunning']=$true}}catch{}
        throw
    } finally {$process.Dispose()}
}

function Remove-BackupPath {
    param([string]$Path,[switch]$Directory)
    if(-not(Test-Path -LiteralPath $Path)){return}
    if([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT){
        Add-Type -AssemblyName Microsoft.VisualBasic
        if($Directory){
            [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($Path,[Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,[Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin)
        }else{
            [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($Path,[Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,[Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin)
        }
    }else{Remove-Item -LiteralPath $Path -Force -Recurse:$Directory}
}
