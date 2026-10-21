# Creates business fixtures through authenticated APIs in an isolated local deployment.
param([switch]$Build)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$directory=Join-Path $root '.local/demo'
$manifestPath=Join-Path $directory 'manifest.json'
$envPath=Join-Path $directory '.env'
$base='http://127.0.0.1:19092'
New-Item -ItemType Directory -Force -Path $directory | Out-Null

function Save-Manifest {
    [IO.File]::WriteAllText($manifestPath+'.tmp',($script:manifest|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath ($manifestPath+'.tmp') -Destination $manifestPath -Force
}
function Remember([string]$Key,$Value) {
    $script:manifest.resources | Add-Member -NotePropertyName $Key -NotePropertyValue $Value -Force
    Save-Manifest
}
function New-DemoSecret {
    $bytes=New-Object byte[] 24
    $random=[Security.Cryptography.RandomNumberGenerator]::Create()
    try {$random.GetBytes($bytes)} finally {$random.Dispose()}
    return [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=')
}
if(Test-Path -LiteralPath $manifestPath){
    $script:manifest=[IO.File]::ReadAllText($manifestPath)|ConvertFrom-Json
    if($manifest.schemaVersion -ne 1 -or $manifest.project -ne 'ai-order-demo' -or $manifest.baseUrl -ne $base){throw 'Demo manifest does not match this isolated environment.'}
    if(-not(Test-Path -LiteralPath $envPath)){throw 'Demo configuration is missing. Restore .local/demo/.env; credentials will not be regenerated over existing data.'}
}else{
    $suffix=[Guid]::NewGuid().ToString('N').Substring(0,8)
    $accounts=[ordered]@{}
    foreach($role in @('customer','owner1','owner2','staff','rider1','rider2')){
        $accounts[$role]=@{username="demo_${role}_$suffix";password=(New-DemoSecret)}
    }
    $script:manifest=[pscustomobject]@{schemaVersion=1;project='ai-order-demo';baseUrl=$base;namespace=$suffix;accounts=[pscustomobject]$accounts;resources=[pscustomobject]@{};completed=$false}
    Save-Manifest
}
& (Join-Path $PSScriptRoot 'initialize-env.ps1') -EnvPath $envPath
$config=@{}
foreach($line in [IO.File]::ReadAllLines($envPath)){if($line -match '^([A-Z_]+)=(.*)$'){$config[$matches[1]]=$matches[2]}}
$compose=@('compose','--env-file',$envPath,'-p','ai-order-demo','-f',(Join-Path $root 'docker-compose.yml'),'-f',(Join-Path $root 'deploy/compose/isolated.yml'),'-f',(Join-Path $root 'deploy/compose/demo.yml'))
& docker @compose config --quiet
if($LASTEXITCODE -ne 0){throw 'Invalid demo Compose configuration.'}
$up=@('up','-d','--wait','--wait-timeout','240')
if($Build){$up+='--build'}
& docker @compose @up
if($LASTEXITCODE -ne 0){throw 'Demo services did not become ready.'}
# Refuse to seed a different process that happens to be listening on our port.
$gateway=& docker @compose ps -q gateway
if($LASTEXITCODE -ne 0 -or -not $gateway){throw 'Demo gateway is missing.'}
$label=& docker inspect $gateway --format '{{index .Config.Labels "com.ai-order.demo"}}'
$published=& docker port $gateway 9090/tcp
if($label -ne 'true' -or $published -ne '127.0.0.1:19092'){throw 'Demo gateway identity/port mismatch.'}

function New-Session([bool]$Management){
    return @{web=(New-Object Microsoft.PowerShell.Commands.WebRequestSession);management=$Management}
}
function Invoke-DemoApi($Session,[string]$Method,[string]$Path,$Body=$null){
    $headers=@{Accept='application/json'}
    if($Session.management){$headers['X-Session-Type']='management'}
    try {
        if($Method -ne 'GET'){
            $csrf=Invoke-RestMethod -Uri ($base+'/auth/csrf') -WebSession $Session.web -Headers $headers -TimeoutSec 15
            $headers['X-XSRF-TOKEN']=$csrf.data.token
        }
        $arguments=@{Uri=($base+$Path);Method=$Method;WebSession=$Session.web;Headers=$headers;TimeoutSec=30}
        if($null -ne $Body){$arguments.ContentType='application/json; charset=utf-8';$arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 10 -Compress))}
        $result=Invoke-RestMethod @arguments
    }catch{
        # Never echo credentials, tokens or the raw server response.
        throw "Demo API failed: $Method $Path. Restore the original private credentials if they were changed; existing accounts will not be reset."
    }
    if($result.code -ne 1){throw "Demo API rejected: $Method $Path"}
    return $result.data
}
function Login($Session,$Account){
    $route=if($Session.management){'/admin/login'}else{'/auth/login'}
    return Invoke-DemoApi $Session 'POST' $route @{username=$Account.username;password=$Account.password}
}
$platform=New-Session $true
$null=Login $platform @{username=$config.PLATFORM_ADMIN_USERNAME;password=$config.PLATFORM_ADMIN_PASSWORD}
$customer=New-Session $false
# Credentials are persisted before registration, allowing safe resume after a lost response.
try {$null=Login $customer $manifest.accounts.customer}
catch {
    if($manifest.completed){throw}
    $null=Invoke-DemoApi $customer 'POST' '/auth/register' @{username=$manifest.accounts.customer.username;password=$manifest.accounts.customer.password;nickname='演示同学'}
}
if(-not $manifest.completed){
    $campusIds=@()
    foreach($name in @('演示东校区','演示西校区')){
        $name="$name $($manifest.namespace)"
        $campus=@(Invoke-DemoApi $platform 'GET' '/platform/campus-options' | Where-Object {$_.name -eq $name})
        if(-not $campus.Count){$id=Invoke-DemoApi $platform 'POST' '/platform/campuses' @{name=$name}}else{$id=$campus[0].id}
        $campusIds+=@([long]$id)
    }
    Remember 'campuses' $campusIds
    $owners=@()
    for($i=0;$i -lt 2;$i++){
        $account=$manifest.accounts.('owner'+($i+1))
        $name=@('演示饭堂','演示饮品店')[$i]+" $($manifest.namespace)"
        $shop=@(Invoke-DemoApi $platform 'GET' '/platform/merchants' | Where-Object {$_.name -eq $name})
        if(-not $shop.Count){$merchant=Invoke-DemoApi $platform 'POST' '/platform/merchants' @{name=$name;ownerUsername=$account.username;ownerPassword=$account.password}}else{$merchant=$shop[0]}
        $owner=New-Session $true
        $identity=Login $owner $account
        if($identity.merchantId -ne $merchant.id){throw 'Demo merchant identity mismatch.'}
        $owners+=@($owner)
        $key='merchant'+($i+1)
        if(-not $manifest.resources.PSObject.Properties[$key]){
            $null=Invoke-DemoApi $owner 'PUT' ('/admin/home-campus/'+$campusIds[$i])
            Remember $key $merchant.id
        }
        foreach($campusId in $campusIds){
            $rules=@(Invoke-DemoApi $owner 'GET' '/admin/delivery-rules' | Where-Object {$_.campus_id -eq $campusId})
            if(-not $rules.Count){$null=Invoke-DemoApi $owner 'PUT' '/admin/delivery-rules' @{campusId=$campusId;enabled=$true;fee=2;minimumAmount=0;etaMin=15;etaMax=40;expectedVersion=0}}
        }
        $dishName=@('招牌鸡肉饭','清爽柠檬水')[$i]
        $dishes=@(Invoke-DemoApi $owner 'GET' '/admin/dishes' | Where-Object {$_.name -eq $dishName})
        if(-not $dishes.Count){$dish=Invoke-DemoApi $owner 'POST' '/admin/dishes' @{name=$dishName;category='演示';price=@(12,6)[$i];stock=100;status=1;allergens='';allergenReviewed=$true}}else{$dish=$dishes[0]}
        $skus=@(Invoke-DemoApi $owner 'GET' '/admin/skus' | Where-Object {$_.dish_id -eq $dish.id -and $_.name -eq '标准份'})
        if(-not $skus.Count){$sku=Invoke-DemoApi $owner 'POST' '/admin/skus' @{dishId=$dish.id;name='标准份';price=@(12,6)[$i];packagingFee=1;stock=100;enabled=$true}}else{$sku=$skus[0].id}
        Remember ('sku'+($i+1)) $sku
        $couponName="演示店铺券 $($manifest.namespace)"
        $coupons=@(Invoke-DemoApi $owner 'GET' '/admin/coupons' | Where-Object {$_.name -eq $couponName})
        $now=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time').DateTime
        $campaign=@{name=$couponName;thresholdAmount=5;discountAmount=1;totalCount=100;claimStart=$now.AddHours(-1).ToString('s');claimEnd=$now.AddDays(30).ToString('s');validStart=$now.AddHours(-1).ToString('s');validEnd=$now.AddDays(30).ToString('s')}
        if(-not $coupons.Count){$null=Invoke-DemoApi $owner 'POST' '/admin/coupons' $campaign}
        $slotKey='reservation'+($i+1)
        if(-not $manifest.resources.PSObject.Properties[$slotKey]){
            $slots=@(Invoke-DemoApi $owner 'GET' '/admin/reservation-slots')
            if(-not $slots.Count){$slot=Invoke-DemoApi $owner 'POST' '/admin/reservations' @{startsAt=$now.AddMinutes(30).ToString('s');endsAt=$now.AddMinutes(60).ToString('s');cutoffAt=$now.AddMinutes(15).ToString('s');prepareMinutes=30;capacity=5}}else{$slot=$slots[0].id}
            Remember $slotKey $slot
        }
    }
    $staff=@(Invoke-DemoApi $owners[0] 'GET' '/admin/staff' | Where-Object {$_.username -eq $manifest.accounts.staff.username})
    if(-not $staff.Count){$null=Invoke-DemoApi $owners[0] 'POST' '/admin/staff' @{username=$manifest.accounts.staff.username;password=$manifest.accounts.staff.password}}
    foreach($key in @('rider1','rider2')){
        $account=$manifest.accounts.$key
        $riders=@(Invoke-DemoApi $platform 'GET' '/platform/riders' | Where-Object {$_.username -eq $account.username})
        if(-not $riders.Count){$null=Invoke-DemoApi $platform 'POST' '/platform/riders' @{username=$account.username;password=$account.password;campusIds=$campusIds}}
    }
    $campaign.name="演示平台券 $($manifest.namespace)";$campaign.thresholdAmount=15;$campaign.discountAmount=2
    $coupons=@(Invoke-DemoApi $platform 'GET' '/platform/coupons' | Where-Object {$_.name -eq $campaign.name})
    if(-not $coupons.Count){$null=Invoke-DemoApi $platform 'POST' '/platform/coupons' $campaign}
    $addresses=@(Invoke-DemoApi $customer 'GET' '/addresses' | Where-Object {$_.campus_id -eq $campusIds[0]})
    if(-not $addresses.Count){$null=Invoke-DemoApi $customer 'POST' '/addresses' @{campusId=$campusIds[0];recipientName='演示同学';phone='13800000000';detail='演示宿舍 101'}}
    $manifest.completed=$true
    Save-Manifest
}
# Verify all roles can still authenticate, without resetting manually changed data.
foreach($role in @('owner1','owner2','staff','rider1','rider2')){$null=Login (New-Session $true) $manifest.accounts.$role}
Write-Host "Demo ready: $base/campus/"
Write-Host 'Owner/staff: /admin/ ; platform: /platform/ ; rider: /rider/'
Write-Host "Private role credentials and fixture IDs: $manifestPath"
Write-Host "Private platform credentials: $envPath"
Write-Host 'Select the demo east campus, claim coupons, add food and drink, review the quote, confirm and simulate payment.'
Write-Host 'Use separate browser profiles for owner/staff/platform/rider. Prepare the order, claim it as a rider and verify the customer delivery code.'
Write-Host 'No orders/payments were seeded. Coupons expire after 30 days; the initial reservation closes after 15 minutes. Re-running preserves edits and expired resources.'
