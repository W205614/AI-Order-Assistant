param([string]$ProjectName='ai-order-assistant',[string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),[string]$OutputDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) 'backups'))
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$file=Join-Path $OutputDirectory ('orders-'+(Get-Date -Format yyyyMMddTHHmmss)+'.sql')
# PTY disabled; capture bytes through a temp file inside the named MySQL container.
& docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') exec -T mysql sh -c 'umask 077; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF ai_order_assistant > /tmp/order-backup.sql'
if($LASTEXITCODE -ne 0){throw 'Database dump failed'}
$container=& docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') ps -q mysql
& docker cp "${container}:/tmp/order-backup.sql" $file
if($LASTEXITCODE -ne 0){throw 'Database dump copy failed'}
& docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') exec -T mysql rm /tmp/order-backup.sql
if((Get-Item -LiteralPath $file).Length -lt 100 -or -not (Select-String -LiteralPath $file -Pattern 'Dump completed')){throw 'Dump verification failed'}
(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash | Set-Content -LiteralPath ($file+'.sha256')
# Delete only verified old dumps within this resolved backup directory; retain 7.
$base=[IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')+'\'
$old=@(Get-ChildItem -LiteralPath $OutputDirectory -Filter 'orders-*.sql' | Sort-Object LastWriteTime -Descending | Select-Object -Skip 7)
foreach($entry in $old){
    $target=[IO.Path]::GetFullPath($entry.FullName)
    if(-not $target.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'Backup target escaped directory'}
    Remove-Item -LiteralPath $target -Force
    Remove-Item -LiteralPath ($target+'.sha256') -Force -ErrorAction SilentlyContinue
}
Write-Host "Verified backup: $file"
