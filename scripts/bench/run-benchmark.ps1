# 청약 동시성 벤치 절차 (수동 실행 가이드 겸 스크립트)
# 전제: docker compose up -d 완료, 시드 1회 실행, tokens.json 생성 완료.
#
#   docker exec -i fracta-postgres psql -U postgres -d fracta < scripts/bench/seed.sql
#   docker exec -i fracta-postgres psql -U postgres -d fracta -t -A -c "SELECT id FROM investor WHERE email LIKE 'bench-%@bench.local' AND email <> 'bench-issuer@bench.local' ORDER BY id" | python scripts/bench/gen_tokens.py > scripts/bench/tokens.json
#
# 전략별로: SUBSCRIPTION_CONCURRENCY 환경변수로 앱을 띄운 뒤 이 스크립트를 3회 실행하고 중앙값을 취한다.
param(
    [Parameter(Mandatory = $true)][string]$Strategy,
    [Parameter(Mandatory = $true)][long]$IssuanceId,
    [int]$Run = 1
)

$ErrorActionPreference = 'Stop'
$benchDir = $PSScriptRoot

Write-Host "== reset DB =="
Get-Content "$benchDir\reset.sql" -Raw | docker exec -i fracta-postgres psql -U postgres -d fracta | Out-Null

Write-Host "== k6 run ($Strategy #$Run) =="
Push-Location $benchDir
k6 run --summary-export "result-$Strategy-$Run.json" -e ISSUANCE_ID=$IssuanceId subscription-load.js
Pop-Location

Write-Host "== 정합성 검증 =="
docker exec fracta-postgres psql -U postgres -d fracta -t -A -c @"
SELECT '배정합=' || COALESCE(SUM(requested_units),0) || ' 주문수=' || COUNT(*) ||
       ' 잔여=' || (SELECT remaining_units FROM issuance WHERE token_symbol='FR-BNCH-001')
FROM subscription_order WHERE status='DEPOSITED';
"@
