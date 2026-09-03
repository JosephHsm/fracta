# 후보 종목코드가 실제로 무엇인지 PLUG에 물어 확인한다.
#
# 목적: 시드에 넣을 상장 리츠·ETF 코드를 기억이나 검색에 의존해 적으면 틀린다.
#       모의 도메인이 종목명을 그대로 돌려주므로, 코드→이름을 직접 확인하고 쓴다.
#
# ETF는 별도 엔드포인트(etfCurrent)를 쓴다. 거기에만 NAV·괴리율(dprt)이 있다.
#
#   scripts\plug\resolve-tickers.ps1
param(
    [string[]]$Reits = @('365550','293940','330590','357120','448730','088260','350520','400760'),
    [string[]]$Etfs  = @('069500','133690')
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

$cfg = @{}
Get-Content "$repo\.env" | Where-Object { $_ -match '^\s*[A-Z]' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    $cfg[$k.Trim()] = ($v -split '#')[0].Trim()
}

$tokenRes = Invoke-RestMethod -Uri "$($cfg['BROKER_AUTH_URL'])/oauth2/token" -Method Post `
    -Body @{ appkey = $cfg['BROKER_APP_KEY']; appsecretkey = $cfg['BROKER_APP_SECRET']
             grant_type = 'client_credentials'; scope = 'oob' } `
    -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 20

$headers = @{
    'x-client-id'     = $cfg['BROKER_APP_KEY']
    'x-client-secret' = $cfg['BROKER_APP_SECRET']
    'authorization'   = "Bearer $($tokenRes.access_token)"
}

function Get-Quote($code, $endpoint) {
    # 실측 실효 한도가 1건/초다 (docs/benchmarks/broker-quota.md)
    Start-Sleep -Milliseconds 1100
    $body = @{ Input_0 = @{ iem_cd = $code; market_cd = 'KRX' } } | ConvertTo-Json -Depth 5
    try {
        Invoke-RestMethod -Uri "$($cfg['BROKER_BASE_URL'])/krstock/quote/v1/$endpoint" `
            -Method Post -Headers $headers -Body $body -ContentType 'application/json' -TimeoutSec 20
    } catch {
        [pscustomobject]@{ error = $_.Exception.Message }
    }
}

"`n=== 리츠·일반주식 (currentPrice) ===`n"
foreach ($code in $Reits) {
    $r = Get-Quote $code 'currentPrice'
    $out = $r.Output_1
    if ($null -eq $out) { "{0}  ✗ {1}" -f $code, ($r.error ?? '응답 없음'); continue }
    "{0}  {1,-24} 현재가 {2,10}  등락률 {3}%" -f $code, $out.iem_nm, $out.stck_prpr, $out.prdy_ctrt
}

"`n=== ETF (etfCurrent — NAV·괴리율 포함) ===`n"
foreach ($code in $Etfs) {
    $r = Get-Quote $code 'etfCurrent'
    $out = $r.Output_1
    if ($null -eq $out) { "{0}  ✗ {1}" -f $code, ($r.error ?? '응답 없음'); continue }
    "{0}  {1,-24} 현재가 {2,9}  NAV {3,9}  괴리율 {4}%  추적오차 {5}%" -f `
        $code, $out.iem_nm, $out.stck_prpr, $out.itmt_last_nav, $out.dprt, $out.trc_errt
}
""
