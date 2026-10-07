param([string]$EnvPath=(Join-Path (Split-Path -Parent $PSScriptRoot) '.env'))
$ErrorActionPreference='Stop'
function New-Secret {
    $bytes=New-Object byte[] 48
    $random=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$random.GetBytes($bytes)}finally{$random.Dispose()}
    [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=')
}
$values=@{}
if(Test-Path -LiteralPath $EnvPath){
    foreach($line in [IO.File]::ReadAllLines($EnvPath)){if($line -match '^([A-Z_]+)=(.*)$'){$values[$matches[1]]=$matches[2]}}
}
$defaults=@{MYSQL_ROOT_PASSWORD=(New-Secret);DB_APP_PASSWORD=(New-Secret);JWT_USER_SECRET=(New-Secret);JWT_ADMIN_SECRET=(New-Secret);AGENT_INTERNAL_API_KEY=(New-Secret);PLATFORM_ADMIN_USERNAME='platform';PLATFORM_ADMIN_PASSWORD=(New-Secret);DEMO_SEED_ENABLED='true';COOKIE_SECURE='false';LLM_API_KEY='';LLM_BASE_URL='https://api.openai.com/v1';LLM_MODEL='gpt-4o-mini';FLYWAY_BASELINE_ON_MIGRATE='false'}
$missing=@($defaults.Keys | Where-Object {-not $values.ContainsKey($_)})
if($missing.Count -gt 0){
    if(Test-Path -LiteralPath $EnvPath){
        $copyDir=Join-Path (Split-Path -Parent $PSScriptRoot) '.local/config-backups'
        New-Item -ItemType Directory -Force -Path $copyDir | Out-Null
        Copy-Item -LiteralPath $EnvPath -Destination (Join-Path $copyDir ((Get-Date -Format yyyyMMddHHmmss)+'.env'))
    }
    foreach($key in $missing){$values[$key]=$defaults[$key]}
    [IO.File]::WriteAllLines($EnvPath,@($values.Keys | Sort-Object | ForEach-Object {"$_=$($values[$_])"}),[Text.UTF8Encoding]::new($false))
}
Write-Host 'Runtime configuration ready. Platform credentials are in the local .env file.'
