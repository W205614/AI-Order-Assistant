param([string]$ProjectName='ai-order-assistant',[string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.env'))
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$values=@{}
foreach($line in [IO.File]::ReadAllLines($EnvPath)){if($line -match '^([A-Z_]+)=(.*)$'){$values[$matches[1]]=$matches[2]}}
$password=$values['DB_APP_PASSWORD']
if([string]::IsNullOrEmpty($password)){throw 'DB_APP_PASSWORD is missing'}
# Escape literals; raw secrets never enter the command arguments or output.
$literal=$password.Replace('\','\\').Replace("'","''")
$sql="CREATE USER IF NOT EXISTS 'ai_order_app'@'%' IDENTIFIED BY '$literal'; ALTER USER 'ai_order_app'@'%' IDENTIFIED BY '$literal'; GRANT ALL PRIVILEGES ON ai_order_assistant.* TO 'ai_order_app'@'%';"
$sql | & docker compose --env-file $EnvPath -p $ProjectName -f (Join-Path $root 'docker-compose.yml') exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'
if($LASTEXITCODE -ne 0){throw 'Application database account provisioning failed'}
Write-Host 'Application account is limited to the application schema.'
