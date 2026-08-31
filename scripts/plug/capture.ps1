# PLUG 실제 응답 캡처 도구.
# phase-05 §6-7 "추측으로 WireMock 스텁 작성 금지" — 스텁은 반드시 이 캡처 결과로 만든다.
#
#   scripts\plug\capture.ps1 -Path /n2/acctinfo
#   scripts\plug\capture.ps1 -Path /krstock/quote/v1/currentPrice -InputData @{ iem_cd='005930'; market_cd='KRX' }
#
# 캡처 파일은 scripts/plug/captured/ 에 저장되며 .gitignore 대상이다(계좌번호 등 포함).
# ($Input 은 PowerShell 예약 변수라 파라미터명으로 쓸 수 없다)
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [hashtable]$InputData = @{},
    [ValidateSet('mock', 'live')][string]$Env = 'mock',
    [switch]$Save
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

# .env 로드
$cfg = @{}
Get-Content "$repo\.env" | Where-Object { $_ -match '^\s*[A-Z]' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    $cfg[$k.Trim()] = ($v -split '#')[0].Trim()
}

# 토큰 발급은 항상 실전 도메인. 데이터 조회는 환경에 따라 분리 (PLUG 고유 제약)
$tokenRes = Invoke-RestMethod -Uri "$($cfg['BROKER_AUTH_URL'])/oauth2/token" -Method Post `
    -Body @{ appkey = $cfg['BROKER_APP_KEY']; appsecretkey = $cfg['BROKER_APP_SECRET']
             grant_type = 'client_credentials'; scope = 'oob' } `
    -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 20

$base = if ($Env -eq 'mock') { $cfg['BROKER_BASE_URL'] } else { $cfg['BROKER_AUTH_URL'] }
$headers = @{
    'x-client-id'     = $cfg['BROKER_APP_KEY']
    'x-client-secret' = $cfg['BROKER_APP_SECRET']
    'authorization'   = "Bearer $($tokenRes.access_token)"
}

Write-Host "POST $base$Path  (env=$Env)"
try {
    $res = Invoke-RestMethod -Uri "$base$Path" -Method Post -Headers $headers `
        -Body (@{ Input_0 = $InputData } | ConvertTo-Json -Depth 6) `
        -ContentType 'application/json; charset=UTF-8' -TimeoutSec 30
} catch {
    Write-Host "HTTP $([int]$_.Exception.Response.StatusCode)"
    $res = $_.ErrorDetails.Message | ConvertFrom-Json
}

$json = $res | ConvertTo-Json -Depth 10
$json

if ($Save) {
    $dir = "$PSScriptRoot\captured"
    New-Item -ItemType Directory -Force $dir | Out-Null
    $name = ($Path.Trim('/') -replace '[/]', '_') + ".json"
    Set-Content "$dir\$name" -Value $json -Encoding utf8
    Write-Host "저장: $dir\$name"
}
