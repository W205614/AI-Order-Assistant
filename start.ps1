param([switch]$Docker,[switch]$Build,[switch]$Foreground,[switch]$Detached,[switch]$Restart,
[string]$ProjectName='ai-order-assistant',[string]$EnvPath=(Join-Path $PSScriptRoot '.env'))
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
& (Join-Path $PSScriptRoot 'scripts/initialize-env.ps1') -EnvPath $EnvPath
$argsCompose=@('compose','--env-file',$EnvPath,'-p',$ProjectName,'-f',(Join-Path $PSScriptRoot 'docker-compose.yml'),'-f',(Join-Path $PSScriptRoot 'docker-compose.operations.yml'))
& docker @argsCompose config --quiet
if($LASTEXITCODE -ne 0){throw 'Compose configuration invalid'}
& docker @argsCompose up -d --wait mysql redis
if($LASTEXITCODE -ne 0){throw 'Database dependencies failed readiness'}
& (Join-Path $PSScriptRoot 'scripts/backup-db.ps1') -ProjectName $ProjectName -EnvPath $EnvPath
& (Join-Path $PSScriptRoot 'scripts/provision-db-user.ps1') -ProjectName $ProjectName -EnvPath $EnvPath
$up=@('up','-d','--wait','--wait-timeout','180')
if($Build){$up+='--build'}
& docker @argsCompose @up
if($LASTEXITCODE -ne 0){throw 'Application failed readiness'}
Write-Host 'Ready: http://localhost:9090/chat/  http://localhost:9090/admin/  http://localhost:9090/platform/'
if($Foreground){& docker @argsCompose logs -f --tail 30 gateway agent}
