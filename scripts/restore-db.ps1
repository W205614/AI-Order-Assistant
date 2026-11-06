param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [string]$EnvPath = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),
    [string]$SourceProject,
    [string]$SourceContainer,
    [switch]$CampusSchema,
    [switch]$IntegrationSchema,
    [ValidateSet('legacy', 'campus', 'integration')][string]$SchemaMode = 'integration',
    [string]$RestoreImage = 'ai-order-assistant-mysql-runtime:8.4.13',
    [string]$ReportPath = (Join-Path (Split-Path -Parent $PSScriptRoot) '.local/restore-report.json')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ($SourceProject -and $SourceContainer) { throw 'Choose SourceProject or SourceContainer, not both' }
if ($IntegrationSchema) {
    if ($PSBoundParameters.ContainsKey('SchemaMode') -and $SchemaMode -ne 'integration') { throw 'Conflicting schema modes' }
    $SchemaMode = 'integration'
}
elseif ($CampusSchema) {
    if ($PSBoundParameters.ContainsKey('SchemaMode') -and $SchemaMode -ne 'campus') { throw 'Conflicting schema modes' }
    $SchemaMode = 'campus'
}
$reportFile = [IO.Path]::GetFullPath($ReportPath)
$backupPath = [IO.Path]::GetFullPath($BackupFile)
if ([IO.Path]::GetExtension($reportFile) -ne '.json' -or $reportFile -eq $backupPath -or $reportFile -eq ($backupPath + '.metadata.json')) { throw 'Choose a separate JSON report path' }
$parent = Split-Path -Parent $reportFile
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$manifestFile = [IO.Path]::ChangeExtension($reportFile, '.media-manifest.json')
$integrityFile = [IO.Path]::ChangeExtension($reportFile, '.integrity.json')
$sourceFile = [IO.Path]::ChangeExtension($reportFile, '.source-integrity.json')
$report = [ordered]@{
    schemaVersion = 2; schemaMode = $SchemaMode; passed = $false; checksumVerified = $false
    independentDatabase = $false; sourceCompared = $false; sourceFingerprintMatched = $null
    backupSHA256 = $null; backupCreatedAt = $null; backupMetadataVerified = $false
    cleanupComplete = $false; testedAt = [DateTimeOffset]::UtcNow.ToString('o')
}
function Write-RestoreReport {
    [IO.File]::WriteAllText($reportFile, ($report | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
}
Write-RestoreReport
$python = Get-Command python -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $python) { $python = Get-Command python3 -CommandType Application -ErrorAction Stop | Select-Object -First 1 }
$token = [guid]::NewGuid().ToString('N')
$name = 'ai-order-restore-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss') + '-' + $token.Substring(0, 8)
$created = $false
$stage = 'backup_validation'
$failure = $false
$oldPassword = $env:MYSQL_ROOT_PASSWORD
$oldAppPassword = $env:DB_APP_PASSWORD
$oldMigrationPassword = $env:DB_MIGRATION_PASSWORD
try {
    $file = (Resolve-Path -LiteralPath $backupPath).Path
    $item = Get-Item -LiteralPath $file
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $item.Length -le 0) { throw 'Backup must be a nonempty regular file' }
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if (-not(Test-Path -LiteralPath ($file + '.sha256')) -or ([IO.File]::ReadAllText($file + '.sha256').Trim().ToLowerInvariant() -ne $hash)) { throw 'Backup checksum mismatch' }
    $report.backupSHA256 = $hash
    $report.checksumVerified = $true
    if (Test-Path -LiteralPath ($file + '.metadata.json')) {
        $metadata = [IO.File]::ReadAllText($file + '.metadata.json') | ConvertFrom-Json
        if (($metadata.schemaVersion -isnot [int] -and $metadata.schemaVersion -isnot [long]) -or $metadata.schemaVersion -ne 1 -or $metadata.backupSHA256 -isnot [string] -or $metadata.backupSHA256 -cne $hash) { throw 'Backup metadata mismatch' }
        if ($metadata.createdAt -is [DateTime]) {
            # PowerShell 7.5+ parses ISO JSON timestamps into DateTime automatically.
            if ($metadata.createdAt.Kind -eq [DateTimeKind]::Unspecified) { throw 'Snapshot time must include a timezone' }
            $snapshotTime = [DateTimeOffset]$metadata.createdAt
        }
        elseif ($metadata.createdAt -is [string] -and $metadata.createdAt -match '^\d{4}-\d{2}-\d{2}T.+(?:Z|[+-]\d{2}:\d{2})$') {
            $snapshotTime = [DateTimeOffset]::Parse($metadata.createdAt, [Globalization.CultureInfo]::InvariantCulture)
        }
        else { throw 'Backup creation time is invalid' }
        if ($snapshotTime -gt [DateTimeOffset]::UtcNow -or $snapshotTime.ToUnixTimeSeconds() -lt 0) { throw 'Backup creation time is invalid' }
        $report.backupCreatedAt = $snapshotTime.ToUniversalTime().ToString('o')
        $report.backupMetadataVerified = $true
    }
    $stage = 'source_comparison'
    if ($SourceProject) {
        $SourceContainer = (& docker compose --env-file $EnvPath -p $SourceProject -f (Join-Path $root 'docker-compose.yml') ps -q mysql).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $SourceContainer) { throw 'Source database not available' }
    }
    if ($SourceContainer) {
        & $python.Source -B (Join-Path $PSScriptRoot 'restore-integrity.py') --container $SourceContainer --schema-mode $SchemaMode --report $sourceFile | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Source integrity verification failed' }
        $source = [IO.File]::ReadAllText($sourceFile) | ConvertFrom-Json
        $report.sourceCompared = $true
    }
    $stage = 'independent_database_start'
    $imageId = (& docker image inspect $RestoreImage --format '{{.Id}}').Trim()
    if ($LASTEXITCODE -ne 0 -or $imageId -notmatch '^sha256:[0-9a-f]{64}$') { throw 'Restore runtime image unavailable' }
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) }finally { $rng.Dispose() }
    $env:MYSQL_ROOT_PASSWORD = [Convert]::ToBase64String($bytes)
    $env:DB_APP_PASSWORD = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    $env:DB_MIGRATION_PASSWORD = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    & docker run -d --name $name --network none --label com.ai-order.restore=true --label "com.ai-order.restore.token=$token" -e MYSQL_ROOT_PASSWORD -e DB_APP_PASSWORD -e DB_MIGRATION_PASSWORD -e MYSQL_DATABASE=ai_order_assistant $imageId | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Independent restore database failed to start' }
    $created = $true
    $report.independentDatabase = $true
    $report.restoreImageId = $imageId
    $deadline = (Get-Date).AddSeconds(180)
    do {
        & docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -h 127.0.0.1 --protocol=TCP -uroot --silent' *> $null
        if ($LASTEXITCODE -eq 0) { break }
        $running = & docker inspect $name --format '{{.State.Running}}' 2>$null
        if ($LASTEXITCODE -ne 0 -or $running -ne 'true') { throw 'Independent restore database stopped before readiness' }
        Start-Sleep -Seconds 2
    }while ((Get-Date) -lt $deadline)
    if ($LASTEXITCODE -ne 0) { throw 'Independent restore database never became ready' }
    $stage = 'sql_import'
    & docker cp $file "${name}:/tmp/restore.sql" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Restore file copy failed' }
    & docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot ai_order_assistant < /tmp/restore.sql' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Restore import failed' }
    $stage = 'integrity_verification'
    & $python.Source -B (Join-Path $PSScriptRoot 'restore-integrity.py') --container $name --schema-mode $SchemaMode --report $integrityFile --media-manifest $manifestFile | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Restored business invariants failed' }
    $integrity = [IO.File]::ReadAllText($integrityFile) | ConvertFrom-Json
    foreach ($key in @('restoredSchemaVersion', 'fingerprintPolicy', 'databaseFingerprintSHA256', 'tableFingerprints', 'integrationChecks', 'invariants', 'mediaAssetCount', 'mediaManifestSHA256')) { $report[$key] = $integrity.$key }
    $report.mediaManifestFile = [IO.Path]::GetFileName($manifestFile)
    if ($SourceContainer) {
        $report.sourceFingerprintMatched = $source.databaseFingerprintSHA256 -ceq $integrity.databaseFingerprintSHA256
        if (-not $report.sourceFingerprintMatched) { throw 'Source and restore fingerprints differ; quiesce source writes before backup' }
    }
    $report.passed = $true
}
catch {
    $failure = $true
    $report.passed = $false
    $report.errorCode = $stage
}
finally {
    $env:MYSQL_ROOT_PASSWORD = $oldPassword
    $env:DB_APP_PASSWORD = $oldAppPassword
    $env:DB_MIGRATION_PASSWORD = $oldMigrationPassword
    if ($created) {
        $label = & docker inspect $name --format '{{index .Config.Labels "com.ai-order.restore.token"}}' 2>$null
        if ($LASTEXITCODE -eq 0 -and $label -ceq $token) {
            & docker rm -fv $name | Out-Null
            $report.cleanupComplete = $LASTEXITCODE -eq 0
        }
    } else { $report.cleanupComplete = $true }
    if (-not $report.cleanupComplete) { $report.passed = $false; $failure = $true; $report.errorCode = 'owned_resource_cleanup' }
    $report.testedAt = [DateTimeOffset]::UtcNow.ToString('o')
    Write-RestoreReport
}
if ($failure) { throw ('Independent restore verification failed at ' + $report.errorCode + '; inspect the private report, no SQL data was logged') }
Write-Host ('Independent restore verified: schema=' + $SchemaMode + ', mediaAssets=' + $report.mediaAssetCount + ', sourceCompared=' + $report.sourceCompared)
