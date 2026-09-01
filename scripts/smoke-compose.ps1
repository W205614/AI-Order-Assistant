param(
    [string]$ComposeFile = "docker-compose.yml",
    [int]$TimeoutSeconds = 120,
    [switch]$LeaveRunning
)

$ErrorActionPreference = 'Stop'
docker compose -f $ComposeFile config --quiet
docker compose -f $ComposeFile up --build -d
try {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $login = $null
    do {
        try {
            $health = Invoke-RestMethod -Uri 'http://localhost:8800/health' -TimeoutSec 5
            $gateway = Invoke-WebRequest -Uri 'http://localhost:9090/' -UseBasicParsing -TimeoutSec 5
            $candidate = Invoke-RestMethod -Method Post -Uri 'http://localhost:9090/auth/login' -ContentType 'application/json' -Body '{"username":"demo","password":"123456"}' -TimeoutSec 5
            if ($health.status -eq 'ok' -and $gateway.StatusCode -eq 200 -and $candidate.code -eq 1) {
                $login = $candidate
                break
            }
        } catch { Start-Sleep -Seconds 2 }
    } while ((Get-Date) -lt $deadline)
    if ($null -eq $login) { throw 'Compose services did not become ready for authenticated traffic in time.' }

    $headers = @{ Authorization = "Bearer $($login.data.token)"; 'Idempotency-Key' = "smoke-$([guid]::NewGuid().ToString('N'))" }
    # Use the seeded dish ID so this Windows PowerShell script is independent
    # of the source-file encoding used for non-ASCII dish names.
    $draftBody = '{"items":[{"dishId":1,"quantity":1}],"remark":"compose smoke"}'
    $draft = Invoke-RestMethod -Method Post -Uri 'http://localhost:9090/order/drafts' -Headers $headers -ContentType 'application/json' -Body $draftBody
    if ($draft.code -ne 1 -or -not $draft.data.id) {
        throw "Draft creation failed: $($draft | ConvertTo-Json -Compress -Depth 8)"
    }
    $confirm = Invoke-RestMethod -Method Post -Uri "http://localhost:9090/order/drafts/$($draft.data.id)/confirm" -Headers $headers
    if ($confirm.code -ne 1 -or -not $confirm.data.id -or -not $confirm.data.userSeq) { throw 'Draft confirmation failed.' }
    $repeat = Invoke-RestMethod -Method Post -Uri "http://localhost:9090/order/drafts/$($draft.data.id)/confirm" -Headers $headers
    if ($repeat.code -ne 1 -or $repeat.data.id -ne $confirm.data.id) { throw 'Idempotent confirmation failed.' }
    # User-facing order APIs address an order by its per-user sequence rather
    # than the internal database ID returned by the confirmation payload.
    $cancel = Invoke-RestMethod -Method Post -Uri "http://localhost:9090/order/$($confirm.data.userSeq)/cancel" -Headers $headers
    if ($cancel.code -ne 1) { throw 'Smoke order cleanup failed.' }
    Write-Host "Compose smoke passed; confirmed and cancelled order seq=$($confirm.data.userSeq)"
} finally {
    if (-not $LeaveRunning) { docker compose -f $ComposeFile down }
}
