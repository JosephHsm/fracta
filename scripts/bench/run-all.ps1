# 3전략 × 3회 실측 후 중앙값 표를 낸다 (phase-04 §k6 측정 시나리오).
# 사전 준비: docker compose up -d / ./gradlew build / seed.sql 적용 / tokens.json 생성.
param(
    [long]$IssuanceId = 1,
    [int]$Runs = 3
)

$ErrorActionPreference = 'Stop'
$bench = $PSScriptRoot
$repo = Split-Path (Split-Path $bench -Parent) -Parent

function Stop-App {
    Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object { Stop-Process -Id $_ -Force -Confirm:$false -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
}

function Median([double[]]$values) {
    $sorted = $values | Sort-Object
    $n = $sorted.Count
    if ($n % 2 -eq 1) { return $sorted[[int](($n - 1) / 2)] }
    return ($sorted[$n / 2 - 1] + $sorted[$n / 2]) / 2
}

$rows = @()
foreach ($strategy in @('atomic', 'pessimistic', 'redis')) {
    Stop-App
    & "$bench\start-app.ps1" -Strategy $strategy | Out-Null

    $tps = @(); $p95 = @(); $ok = @(); $sold = @(); $failed = @(); $transport = @(); $consistent = @()
    for ($run = 1; $run -le $Runs; $run++) {
        Get-Content "$bench\reset.sql" -Raw | docker exec -i fracta-postgres psql -U postgres -d fracta | Out-Null
        Push-Location $bench
        k6 run --quiet --summary-export "result-$strategy-$run.json" -e ISSUANCE_ID=$IssuanceId subscription-load.js *> "k6-$strategy-$run.log"
        Pop-Location

        $j = Get-Content "$bench\result-$strategy-$run.json" -Raw | ConvertFrom-Json
        $tps += [double]$j.metrics.http_reqs.rate
        $p95 += [double]$j.metrics.http_req_duration.'p(95)'
        $ok += [int]($j.metrics.successful_orders.count ?? 0)
        $sold += [int]($j.metrics.sold_out_rejections.count ?? 0)
        $failed += [int]($j.metrics.real_failures.count ?? 0)
        $transport += [int]($j.metrics.transport_errors.count ?? 0)

        # 정합성: 접수된 청약 수량 총합이 발행량과 정확히 일치하는가
        $allotted = docker exec fracta-postgres psql -U postgres -d fracta -t -A -c `
            "SELECT COALESCE(SUM(requested_units),0) FROM subscription_order WHERE status='DEPOSITED'"
        $remaining = docker exec fracta-postgres psql -U postgres -d fracta -t -A -c `
            "SELECT remaining_units FROM issuance WHERE id=$IssuanceId"
        $consistent += "$allotted/$remaining"
        Write-Host "[$strategy #$run] tps=$([math]::Round($tps[-1],1)) p95=$([math]::Round($p95[-1]))ms ok=$($ok[-1]) sold=$($sold[-1]) fail=$($failed[-1]) transport=$($transport[-1]) 배정합=$allotted 잔여=$remaining"
    }

    $rows += [PSCustomObject]@{
        Strategy   = $strategy
        TPS        = [math]::Round((Median $tps), 1)
        P95ms      = [math]::Round((Median $p95))
        Success    = ($ok -join '/')
        SoldOut    = ($sold -join '/')
        RealFail   = ($failed -join '/')
        Transport  = ($transport -join '/')
        Allotted   = ($consistent -join ' ')
    }
}

Stop-App
$rows | Format-Table -AutoSize
$rows | ConvertTo-Json -Depth 3 | Set-Content "$bench\summary.json"
Write-Host "저장: $bench\summary.json"
