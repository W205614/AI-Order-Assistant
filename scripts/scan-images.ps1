param(
    [string]$ProjectName='ai-order-assistant',
    [string]$OutputDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) '.local/security'),
    [string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.github/compose-ci.env'),
    [switch]$SkipPublic
)
$ErrorActionPreference='Stop'
$python=Get-Command python -CommandType Application -ErrorAction SilentlyContinue|Select-Object -First 1
if(-not $python){$python=Get-Command python3 -CommandType Application -ErrorAction Stop|Select-Object -First 1}
$arguments=@('-X','utf8','-B',(Join-Path $PSScriptRoot 'scan_deployment_images.py'),'--project-name',$ProjectName,'--output-directory',[IO.Path]::GetFullPath($OutputDirectory),'--env-file',[IO.Path]::GetFullPath($EnvPath))
if($SkipPublic){$arguments+='--skip-public'}
& $python.Source @arguments
if($LASTEXITCODE -ne 0){throw 'Deployment image security gate failed; inspect this run manifest and raw reports.'}
