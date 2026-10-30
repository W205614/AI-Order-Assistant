param([string]$ProjectName='ai-order-assistant',[string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.env'))
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$values=@{}
foreach($line in [IO.File]::ReadAllLines($EnvPath)){if($line -match '^([A-Z_]+)=(.*)$'){$values[$matches[1]]=$matches[2]}}
foreach($key in @('DB_APP_PASSWORD','DB_MIGRATION_PASSWORD')){
    if([string]::IsNullOrEmpty($values[$key])){throw "$key is missing"}
}
$applicationHex=([BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($values['DB_APP_PASSWORD']))).Replace('-','')
$migrationHex=([BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($values['DB_MIGRATION_PASSWORD']))).Replace('-','')
$sql="SET @application_password=CONVERT(0x$applicationHex USING utf8mb4);`nSET @migration_password=CONVERT(0x$migrationHex USING utf8mb4);`n"+[IO.File]::ReadAllText((Join-Path $root 'deploy/mysql/provision-users.sql'))
$sql | & docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'
if($LASTEXITCODE -ne 0){throw 'Application database account provisioning failed'}
Write-Host 'Database runtime account has DML only; migrations use a separate account.'
