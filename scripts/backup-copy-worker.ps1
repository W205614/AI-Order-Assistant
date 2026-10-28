param([string]$Source,[string]$Destination,[string]$ExpectedHash='')
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination)|Out-Null
Copy-Item -LiteralPath $Source -Destination ($Destination+'.tmp')
if((Get-FileHash -LiteralPath ($Destination+'.tmp') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedHash){throw 'Extra backup checksum mismatch'}
Move-Item -LiteralPath ($Destination+'.tmp') -Destination $Destination
[IO.File]::WriteAllText(($Destination+'.sha256.tmp'),$ExpectedHash,[Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath ($Destination+'.sha256.tmp') -Destination ($Destination+'.sha256')
