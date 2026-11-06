param([string]$Source, [string]$Destination, [string]$ExpectedHash = '')
$ErrorActionPreference = 'Stop'
$metadata = [IO.File]::ReadAllText($Source + '.metadata.json')
$details = $metadata | ConvertFrom-Json
$createdText = $details.createdAt
if ($createdText -is [DateTime] -or $createdText -is [DateTimeOffset]) { $createdText = $createdText.ToString('o', [Globalization.CultureInfo]::InvariantCulture) }
$created = [DateTimeOffset]::MinValue
if ((-not($details.schemaVersion -is [int] -or $details.schemaVersion -is [long])) -or $details.schemaVersion -ne 1 -or
    $details.backupSHA256 -isnot [string] -or $details.backupSHA256 -cnotmatch '^[a-f0-9]{64}$' -or $details.backupSHA256 -cne $ExpectedHash -or
    $createdText -isnot [string] -or $createdText -cnotmatch '^\d{4}-\d{2}-\d{2}T.+(?:Z|[+-]\d{2}:\d{2})$' -or
    -not [DateTimeOffset]::TryParse($createdText, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$created) -or
    $created.ToUnixTimeSeconds() -lt 0 -or $created -gt [DateTimeOffset]::UtcNow) { throw 'Source backup creation metadata is invalid' }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
Copy-Item -LiteralPath $Source -Destination ($Destination + '.tmp')
if ((Get-FileHash -LiteralPath ($Destination + '.tmp') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedHash) { throw 'Extra backup checksum mismatch' }
Copy-Item -LiteralPath ($Source + '.metadata.json') -Destination ($Destination + '.metadata.json.tmp')
if ([IO.File]::ReadAllText($Destination + '.metadata.json.tmp') -cne $metadata) { throw 'Extra backup creation metadata mismatch' }
[IO.File]::WriteAllText(($Destination + '.sha256.tmp'), $ExpectedHash, [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath ($Destination + '.tmp') -Destination $Destination
Move-Item -LiteralPath ($Destination + '.sha256.tmp') -Destination ($Destination + '.sha256')
Move-Item -LiteralPath ($Destination + '.metadata.json.tmp') -Destination ($Destination + '.metadata.json')
