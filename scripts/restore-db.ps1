param([Parameter(Mandatory=$true)][string]$BackupFile,[string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),
      [string]$SourceProject,[string]$ReportPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.local/restore-report.json'))
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$file=(Resolve-Path -LiteralPath $BackupFile).Path
$hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
if(-not(Test-Path -LiteralPath ($file+'.sha256')) -or ([IO.File]::ReadAllText($file+'.sha256').Trim() -ne $hash)){throw 'Backup checksum mismatch'}
$name='ai-order-restore-'+(Get-Date -Format yyyyMMddHHmmss)
if($name -notmatch '^ai-order-restore-[0-9]{14}$'){throw 'Invalid restore container name'}
$fingerprint="SELECT JSON_OBJECT('merchants',(SELECT COUNT(*) FROM merchant),'users',(SELECT COUNT(*) FROM user),'dishes',(SELECT COUNT(*) FROM dish),'stock',(SELECT COALESCE(SUM(stock),0) FROM dish),'orders',(SELECT COUNT(*) FROM orders),'items',(SELECT COUNT(*) FROM order_item),'inventoryLedger',(SELECT COUNT(*) FROM inventory_ledger),'payments',(SELECT COUNT(*) FROM payment_record),'events',(SELECT COUNT(*) FROM order_event),'audits',(SELECT COUNT(*) FROM audit_log),'orderChecksum',(SELECT BIT_XOR(CRC32(CONCAT_WS('|',id,merchant_id,user_id,total_amount,status,payment_status,inventory_released))) FROM orders),'stockChecksum',(SELECT BIT_XOR(CRC32(CONCAT_WS('|',id,merchant_id,stock,stock_version))) FROM dish),'orphanItems',(SELECT COUNT(*) FROM order_item i LEFT JOIN orders o ON o.id=i.order_id AND o.merchant_id=i.merchant_id WHERE o.id IS NULL),'orphanDraftItems',(SELECT COUNT(*) FROM order_draft_item i LEFT JOIN order_draft d ON d.id=i.draft_id AND d.merchant_id=i.merchant_id WHERE d.id IS NULL));"
$source=$null
if($SourceProject){
    $source=$fingerprint | & docker compose --env-file $EnvPath -p $SourceProject -f (Join-Path $root 'docker-compose.yml') exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N ai_order_assistant'
    if($LASTEXITCODE -ne 0){throw 'Source fingerprint failed'}
}
# A fresh, independently named database; no source volumes are mounted.
$secretBytes=New-Object byte[] 32
$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
try{$rng.GetBytes($secretBytes)}finally{$rng.Dispose()}
$oldPassword=$env:MYSQL_ROOT_PASSWORD
$env:MYSQL_ROOT_PASSWORD=[Convert]::ToBase64String($secretBytes)
try{
    & docker run -d --name $name --label com.ai-order.restore=true -e MYSQL_ROOT_PASSWORD -e MYSQL_DATABASE=ai_order_assistant mysql:8.4 | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Restore container failed'}
    $deadline=(Get-Date).AddSeconds(180)
    do{
        & docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -h 127.0.0.1 --protocol=TCP -uroot --silent' *> $null
        if($LASTEXITCODE -eq 0){break}
        Start-Sleep -Seconds 2
    }while((Get-Date)-lt $deadline)
    if($LASTEXITCODE -ne 0){throw 'Restore database never became ready'}
    & docker cp $file "${name}:/tmp/restore.sql"
    if($LASTEXITCODE -ne 0){throw 'Restore file copy failed'}
    & docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot ai_order_assistant < /tmp/restore.sql'
    if($LASTEXITCODE -ne 0){throw 'Restore import failed'}
    $restored=$fingerprint | & docker exec -i $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N ai_order_assistant'
    if($LASTEXITCODE -ne 0){throw 'Restore fingerprint failed'}
    $data=$restored | ConvertFrom-Json
    if($data.orphanItems -ne 0 -or $data.orphanDraftItems -ne 0){throw 'Restored tenant ownership is inconsistent'}
    if($source -and $source -ne $restored){throw 'Source and restored fingerprints differ; quiesce writes before taking the backup'}
    $report=@{passed=$true;backupSHA256=$hash;fingerprint=$data;sourceCompared=[bool]$source;testedAt=(Get-Date -Format o)}
    $parent=Split-Path -Parent $ReportPath
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    [IO.File]::WriteAllText($ReportPath,($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    Write-Host 'Independent restore, checksum and tenant ownership checks passed.'
}finally{
    $env:MYSQL_ROOT_PASSWORD=$oldPassword
    $label=& docker inspect $name --format '{{index .Config.Labels "com.ai-order.restore"}}' 2>$null
    if($label -eq 'true'){& docker rm -fv $name | Out-Null}
}
