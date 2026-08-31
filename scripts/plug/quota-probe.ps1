# 증권사 호출 유량 실측 (phase-05 §7 문서화 소재).
#
# 목적: PLUG의 초당 제한이 공식 문서에 수치로 공개돼 있지 않다. 직접 올려가며
#       IGW429xx 가 처음 나오는 지점을 찾는다. 결과는 docs/benchmarks/broker-quota.md 에 기록한다.
#
#   scripts\plug\quota-probe.ps1 -Rps 4 -Seconds 10
param(
    [int]$Rps = 4,
    [int]$Seconds = 10,
    [string]$Ticker = '005930',
    # 버스트 모드: BurstSize 건을 연속 전송한 뒤 BurstSize 초 쉰다.
    # 평균 속도는 1건/초로 같지만 순간 집중이 다르다 — 토큰버킷 방식의 실패를 재현한다.
    [int]$BurstSize = 0,
    [int]$BurstRounds = 3
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

$script:headers = @{
    'x-client-id'     = $cfg['BROKER_APP_KEY']
    'x-client-secret' = $cfg['BROKER_APP_SECRET']
    'authorization'   = "Bearer $($tokenRes.access_token)"
}
$script:url = "$($cfg['BROKER_BASE_URL'])/krstock/quote/v1/currentPrice"
$script:body = @{ Input_0 = @{ iem_cd = $Ticker; market_cd = 'KRX' } } | ConvertTo-Json -Depth 5

$script:ok = 0; $script:quota = 0; $script:other = 0
$script:codes = @{}
$script:latencies = New-Object System.Collections.Generic.List[double]
$intervalMs = [int](1000 / $Rps)

function Invoke-One {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Uri $script:url -Method Post -Headers $script:headers -Body $script:body `
            -ContentType 'application/json; charset=UTF-8' -TimeoutSec 20
        $sw.Stop()
        $code = $r.rsp_cd
    } catch {
        $sw.Stop()
        $code = try { ($_.ErrorDetails.Message | ConvertFrom-Json).rsp_cd } catch { "HTTP$([int]$_.Exception.Response.StatusCode)" }
    }
    $script:latencies.Add($sw.Elapsed.TotalMilliseconds)
    $script:codes[$code] = 1 + ($script:codes[$code] ?? 0)
    if ($code -eq '00000') { $script:ok++ }
    elseif ($code -like 'IGW429*') { $script:quota++ }
    else { $script:other++ }
    return $sw.Elapsed.TotalMilliseconds
}

if ($BurstSize -gt 0) {
    Write-Host "버스트 모드: $BurstSize 건 연속 x $BurstRounds 회 (평균 1건/초)"
    for ($round = 1; $round -le $BurstRounds; $round++) {
        for ($i = 0; $i -lt $BurstSize; $i++) { Invoke-One | Out-Null }
        if ($round -lt $BurstRounds) { Start-Sleep -Seconds $BurstSize }
    }
    $sortedB = $latencies | Sort-Object
    $p95B = if ($sortedB.Count -gt 0) { $sortedB[[int][Math]::Floor($sortedB.Count * 0.95) - 1] } else { 0 }
    [PSCustomObject]@{
        모드      = "버스트 $BurstSize x $BurstRounds (평균 1건/초)"
        총호출    = $latencies.Count
        성공      = $ok
        유량초과  = $quota
        기타오류  = $other
        평균ms    = [math]::Round(($latencies | Measure-Object -Average).Average, 1)
        p95ms     = [math]::Round($p95B, 1)
        코드분포  = ($codes.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '
    }
    return
}

Write-Host "목표 $Rps 건/초 x $Seconds 초 = 약 $($Rps * $Seconds) 건"
$deadline = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Uri $url -Method Post -Headers $headers -Body $body `
            -ContentType 'application/json; charset=UTF-8' -TimeoutSec 20
        $sw.Stop()
        $code = $r.rsp_cd
    } catch {
        $sw.Stop()
        $code = try { ($_.ErrorDetails.Message | ConvertFrom-Json).rsp_cd } catch { "HTTP$([int]$_.Exception.Response.StatusCode)" }
    }
    $latencies.Add($sw.Elapsed.TotalMilliseconds)
    $codes[$code] = 1 + ($codes[$code] ?? 0)
    if ($code -eq '00000') { $ok++ }
    elseif ($code -like 'IGW429*') { $quota++ }
    else { $other++ }

    $remain = $intervalMs - $sw.Elapsed.TotalMilliseconds
    if ($remain -gt 0) { Start-Sleep -Milliseconds $remain }
}

$sorted = $latencies | Sort-Object
$p95 = if ($sorted.Count -gt 0) { $sorted[[int][Math]::Floor($sorted.Count * 0.95) - 1] } else { 0 }
[PSCustomObject]@{
    목표RPS   = $Rps
    총호출    = $latencies.Count
    성공      = $ok
    유량초과  = $quota
    기타오류  = $other
    평균ms    = [math]::Round(($latencies | Measure-Object -Average).Average, 1)
    p95ms     = [math]::Round($p95, 1)
    코드분포  = ($codes.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '
}
