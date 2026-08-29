param(
    [string]$ComposeFile = "docker-compose.yml",
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
docker compose -f $ComposeFile config --quiet
docker compose -f $ComposeFile up --build -d
try {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $health = Invoke-RestMethod -Uri 'http://localhost:8800/health' -TimeoutSec 5
            $gateway = Invoke-WebRequest -Uri 'http://localhost:9090/' -UseBasicParsing -TimeoutSec 5
            if ($health.status -eq 'ok' -and $gateway.StatusCode -eq 200) { break }
        } catch { Start-Sleep -Seconds 2 }
    } while ((Get-Date) -lt $deadline)
    if ((Get-Date) -ge $deadline) { throw 'Compose services did not become healthy in time.' }

    $login = Invoke-RestMethod -Method Post -Uri 'http://localhost:9090/auth/login' -ContentType 'application/json' -Body '{"username":"demo","password":"123456"}'
    if ($login.code -ne 1) { throw 'Demo login failed.' }
    $headers = @{ Authorization = "Bearer $($login.data.token)"; 'Idempotency-Key' = "smoke-$([guid]::NewGuid().ToString('N'))" }
    $draftBody = '{"items":[{"dishName":"鱼香肉丝饭","quantity":1}],"remark":"compose smoke"}'
    $draft = Invoke-RestMethod -Method Post -Uri 'http://localhost:9090/order/drafts' -Headers $headers -ContentType 'application/json' -Body $draftBody
    if ($draft.code -ne 1 -or -not $draft.data.id) { throw 'Draft creation failed.' }
    $confirm = Invoke-RestMethod -Method Post -Uri "http://localhost:9090/order/drafts/$($draft.data.id)/confirm" -Headers $headers
    if ($confirm.code -ne 1 -or -not $confirm.data.id) { throw 'Draft confirmation failed.' }
    $repeat = Invoke-RestMethod -Method Post -Uri "http://localhost:9090/order/drafts/$($draft.data.id)/confirm" -Headers $headers
    if ($repeat.code -ne 1 -or $repeat.data.id -ne $confirm.data.id) { throw 'Idempotent confirmation failed.' }
    Write-Host "Compose smoke passed; order id=$($confirm.data.id)"
} finally {
    docker compose -f $ComposeFile down
}
