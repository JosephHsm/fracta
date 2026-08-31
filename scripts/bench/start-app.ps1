# 벤치용 앱 기동. 전략을 환경변수로 넘기고 부팅 완료(actuator UP)까지 대기한다.
param(
    [Parameter(Mandatory = $true)][ValidateSet('atomic', 'pessimistic', 'redis')][string]$Strategy,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$logDir = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Force $logDir | Out-Null

$env:SUBSCRIPTION_CONCURRENCY = $Strategy
$jar = Get-ChildItem "$repo\build\libs\*-SNAPSHOT.jar" | Where-Object { $_.Name -notlike '*plain*' } | Select-Object -First 1
if (-not $jar) { throw "bootJar 산출물이 없다. ./gradlew build 먼저 실행하라." }

# 8080을 이미 점유한 앱이 있으면 먼저 내린다. 그러지 않으면 새 JVM은 바인드에 실패하는데
# 헬스체크는 옛 앱이 응답해 "기동 성공"으로 오인한다 (실제로 오측정이 발생했다).
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object { Stop-Process -Id $_ -Force -Confirm:$false -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 3

# 벤치 전용 튜닝 — 세 전략 모두 동일 조건으로 측정한다 (문서에 명시).
# 500 동시 연결을 받아내고(accept-count), 커넥션 풀이 병목이 되지 않게 한다.
$tuning = @(
    '--server.tomcat.accept-count=1000',
    '--server.tomcat.max-connections=2000',
    '--server.tomcat.threads.max=200',
    '--spring.datasource.hikari.maximum-pool-size=50',
    '--spring.datasource.hikari.minimum-idle=10'
)

$proc = Start-Process -FilePath 'java' `
    -ArgumentList (@('-jar', $jar.FullName, "--subscription.concurrency=$Strategy") + $tuning) `
    -WorkingDirectory $repo -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$logDir\app-$Strategy.log" `
    -RedirectStandardError "$logDir\app-$Strategy.err.log"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    if ($proc.HasExited) { throw "앱 프로세스가 종료됐다 (exit=$($proc.ExitCode)) — $logDir\app-$Strategy.log 확인" }
    try {
        $health = Invoke-RestMethod 'http://localhost:8080/actuator/health' -TimeoutSec 2
        if ($health.status -eq 'UP') {
            # 응답한 쪽이 방금 띄운 프로세스인지 확인한다
            $owner = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty OwningProcess -Unique
            if ($owner -notcontains $proc.Id) { throw "8080을 다른 프로세스($owner)가 점유 중이다" }
            Write-Host "app up (pid=$($proc.Id), strategy=$Strategy)"
            return $proc.Id
        }
    } catch { Start-Sleep -Seconds 2 }
}
throw "앱 기동 실패 — $logDir\app-$Strategy.log 확인"
