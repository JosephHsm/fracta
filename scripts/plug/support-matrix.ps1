# 모의(moapi) / 실전(api) 도메인별 엔드포인트 지원 여부를 실측한다.
#
# 배경: 2026-09-03 모의 도메인 시세 조회가 IGW40023으로 막힌 것을 발견했다.
#       "어디까지 막혔나"를 문서가 아니라 호출로 확인한다. 공식 문서에는
#       모의 지원 범위 표가 없다.
#
# 조회 전용 엔드포인트만 부른다. 주문 계열은 절대 넣지 않는다.
#
#   scripts\plug\support-matrix.ps1
$ErrorActionPreference = 'SilentlyContinue'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$cfg = @{}
Get-Content "$repo\.env" | Where-Object { $_ -match '^\s*[A-Z]' } | ForEach-Object {
    $k, $v = $_ -split '=', 2; $cfg[$k.Trim()] = ($v -split '#')[0].Trim()
}
$tok = Invoke-RestMethod -Uri "$($cfg['BROKER_AUTH_URL'])/oauth2/token" -Method Post `
    -Body @{ appkey=$cfg['BROKER_APP_KEY']; appsecretkey=$cfg['BROKER_APP_SECRET']
             grant_type='client_credentials'; scope='oob' } `
    -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 20
$h = @{ 'x-client-id'=$cfg['BROKER_APP_KEY']; 'x-client-secret'=$cfg['BROKER_APP_SECRET']
        'authorization'="Bearer $($tok.access_token)" }

# 조회 전용. 주문(order/*)은 이 목록에 넣지 않는다.
$targets = @(
    @{ p='/n2/acctinfo';                       i=@{} ;                                                    n='계좌 조회(대조군)' }
    @{ p='/krstock/quote/v1/currentPrice';     i=@{iem_cd='005930'; market_cd='KRX'};                     n='현재가' }
    @{ p='/krstock/quote/v1/currentDaily';     i=@{iem_cd='005930'; market_cd='KRX'};                     n='일별 시세' }
    @{ p='/krstock/quote/v1/period';           i=@{iem_cd='005930'; market_cd='KRX'; inq_strt_dt='20260801'; inq_end_dt='20260903'; pd_dv_cd='D'}; n='기간 시세' }
    @{ p='/krstock/quote/v1/currentExecution'; i=@{iem_cd='005930'; market_cd='KRX'};                     n='체결 내역' }
    @{ p='/krstock/quote/v1/currentInvestor';  i=@{iem_cd='005930'; market_cd='KRX'};                     n='투자자별' }
    @{ p='/krstock/quote/v1/etfCurrent';       i=@{iem_cd='069500'; market_cd='KRX'};                     n='ETF 현재가(NAV·괴리율)' }
    @{ p='/krstock/quote/v1/etfComponents';    i=@{iem_cd='069500'; market_cd='KRX'};                     n='ETF 구성종목' }
    @{ p='/krstock/quote/v1/afterHoursCurrent';i=@{iem_cd='005930'; market_cd='KRX'};                     n='시간외 현재가' }
    @{ p='/krstock/quote/v1/currentAfterHoursDaily';    i=@{iem_cd='005930'; market_cd='KRX'};             n='시간외 일별' }
    @{ p='/krstock/quote/v1/currentAfterHoursExecution';i=@{iem_cd='005930'; market_cd='KRX'};             n='시간외 체결' }
    @{ p='/krstock/quote/v1/afterHoursExpected';        i=@{iem_cd='005930'; market_cd='KRX'};             n='시간외 예상체결' }
)

function Probe($base, $t) {
    Start-Sleep -Milliseconds 1200   # 실효 한도 1건/초
    $body = @{ Input_0 = $t.i } | ConvertTo-Json -Depth 6
    try {
        $r = Invoke-RestMethod -Uri "$base$($t.p)" -Method Post -Headers $h -Body $body `
             -ContentType 'application/json; charset=UTF-8' -TimeoutSec 25
        if ($r.rsp_cd -and $r.rsp_cd -ne '00000') { return "△ $($r.rsp_cd)" }
        return 'OK'
    } catch {
        $b = $_.ErrorDetails.Message
        if ($b -match '"rsp_cd"\s*:\s*"([^"]+)"') { return "✗ $($matches[1])" }
        return "✗ $([int]$_.Exception.Response.StatusCode)"
    }
}

"`n{0,-34} {1,-22} {2,-14} {3}" -f '엔드포인트', '용도', '모의(moapi)', '실전(api)'
'-' * 88
foreach ($t in $targets) {
    $m = Probe $cfg['BROKER_BASE_URL'] $t
    $l = Probe $cfg['BROKER_AUTH_URL'] $t
    "{0,-34} {1,-22} {2,-14} {3}" -f $t.p.Replace('/krstock/quote/v1','…'), $t.n, $m, $l
}
""
