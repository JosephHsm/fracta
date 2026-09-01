package com.fracta.issuance.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.audit.api.Auditable;
import com.fracta.common.config.AdvisoryLockIds;
import com.fracta.common.money.Money;
import com.fracta.common.money.Units;
import com.fracta.external.broker.MarketDataPort;
import com.fracta.issuance.api.ProspectusUploadedEvent;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.domain.IssuanceValidationException;
import com.fracta.issuance.domain.UnderlyingAsset;
import com.fracta.issuance.infrastructure.IssuanceRepository;
import com.fracta.issuance.infrastructure.UnderlyingAssetRepository;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;

/** 발행 계획·심볼 발급·상태 전이 (IS-02 ~ IS-07). */
@Service
public class IssuanceService {

    public static final long MIN_TOTAL_UNITS = 100;
    public static final long MAX_TOTAL_UNITS = 1_000_000;
    public static final long MIN_UNIT_PRICE = 100;
    public static final long MAX_TOTAL_AMOUNT = 10_000_000_000L; // 100억원
    private static final double PREMIUM_WARN_PERCENT = 30.0;

    private static final Logger log = LoggerFactory.getLogger(IssuanceService.class);

    public record CreateResult(long issuanceId, String tokenSymbol, List<String> warnings) {
    }

    public record TransitionResult(long issuanceId, String from, String to) {
    }

    private final IssuanceRepository issuances;
    private final UnderlyingAssetRepository assets;
    private final MarketDataPort marketData;
    private final LedgerPort ledger;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public IssuanceService(IssuanceRepository issuances, UnderlyingAssetRepository assets,
                           MarketDataPort marketData, LedgerPort ledger, JdbcTemplate jdbc,
                           ApplicationEventPublisher events) {
        this.issuances = issuances;
        this.assets = assets;
        this.marketData = marketData;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.events = events;
    }

    // ── 생성 (IS-02, IS-05) ─────────────────────────────────────

    @Transactional
    public CreateResult create(long assetId, long totalUnits, long unitPrice,
                               Instant subscriptionStartAt, Instant subscriptionEndAt) {
        return create(assetId, totalUnits, unitPrice, subscriptionStartAt, subscriptionEndAt,
                Issuance.AllotmentMethod.FCFS, 3);
    }

    @Transactional
    public CreateResult create(long assetId, long totalUnits, long unitPrice,
                               Instant subscriptionStartAt, Instant subscriptionEndAt,
                               Issuance.AllotmentMethod allotmentMethod, int riskGrade) {
        UnderlyingAsset asset = assets.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("기초자산이 없다: " + assetId));

        validatePlan(totalUnits, unitPrice);

        List<String> warnings = premiumWarnings(asset, unitPrice);
        String symbol = allocateSymbol(asset.assetCode());
        Issuance issuance = new Issuance(assetId, symbol, totalUnits, unitPrice,
                subscriptionStartAt, subscriptionEndAt);
        issuance.configureAllotment(allotmentMethod, riskGrade);
        issuance = issuances.save(issuance);
        return new CreateResult(issuance.id(), symbol, warnings);
    }

    private void validatePlan(long totalUnits, long unitPrice) {
        if (totalUnits < MIN_TOTAL_UNITS || totalUnits > MAX_TOTAL_UNITS) {
            throw IssuanceValidationException.unitsOutOfRange(totalUnits);
        }
        if (unitPrice < MIN_UNIT_PRICE) {
            throw IssuanceValidationException.priceTooLow(unitPrice);
        }
        // Money.multiply가 곱셈 시점 오버플로우를 VALID_AMOUNT_OVERFLOW로 잡는다
        Money totalAmount = Money.of(unitPrice).multiply(totalUnits);
        if (totalAmount.compareTo(Money.of(MAX_TOTAL_AMOUNT)) > 0) {
            throw IssuanceValidationException.totalAmountExceeded(totalUnits, unitPrice);
        }
    }

    private List<String> premiumWarnings(UnderlyingAsset asset, long unitPrice) {
        List<String> warnings = new ArrayList<>();
        if (asset.brokerTicker() == null || asset.brokerTicker().isBlank()) {
            return warnings;
        }
        long marketPrice = marketData.getCurrentPrice(asset.brokerTicker()).price().amount();
        long referencePrice = Math.max(1, Math.round((double) marketPrice / asset.splitRatio()));
        double premium = (unitPrice - referencePrice) * 100.0 / referencePrice;
        if (Math.abs(premium) > PREMIUM_WARN_PERCENT) {
            String warning = "발행가(%d원)가 시세 환산 참조가(%d원) 대비 %.1f%% 괴리 — ±30%% 초과 (경고, 차단 아님)"
                    .formatted(unitPrice, referencePrice, premium);
            warnings.add(warning);
            log.warn("issuance premium warning: assetCode={} {}", asset.assetCode(), warning);
        }
        return warnings;
    }

    /**
     * 토큰 심볼 발급 (IS-05): FR-{자산코드}-{연번 3자리}.
     * 자산코드별 advisory lock으로 연번 발급을 직렬화하고, UNIQUE 제약을 최후 방어선으로 둔다.
     */
    private String allocateSymbol(String assetCode) {
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)",
                ps -> {
                    ps.setInt(1, AdvisoryLockIds.TOKEN_SYMBOL_CLASS);
                    ps.setInt(2, assetCode.hashCode());
                },
                rs -> null);

        String prefix = "FR-" + assetCode + "-";
        int next = issuances.findTopByTokenSymbolStartingWithOrderByTokenSymbolDesc(prefix)
                .map(existing -> Integer.parseInt(existing.tokenSymbol().substring(prefix.length())) + 1)
                .orElse(1);
        if (next > 999) {
            throw new IllegalStateException("자산코드 %s의 연번이 소진됐다".formatted(assetCode));
        }
        return prefix + "%03d".formatted(next);
    }

    // ── 상태 전이 (IS-04, IS-06, IS-07) ─────────────────────────

    @Transactional
    public TransitionResult submit(long issuanceId) {
        return transition(issuanceId, IssuanceStatus.PENDING_APPROVAL);
    }

    @Transactional
    @Auditable(action = "ISSUANCE_APPROVE", targetType = "ISSUANCE")
    public TransitionResult approve(long issuanceId) {
        return transition(issuanceId, IssuanceStatus.APPROVED);
    }

    @Transactional
    @Auditable(action = "ISSUANCE_REJECT", targetType = "ISSUANCE")
    public TransitionResult reject(long issuanceId) {
        return transition(issuanceId, IssuanceStatus.REJECTED);
    }

    @Transactional
    public TransitionResult openSubscription(long issuanceId) {
        Issuance issuance = load(issuanceId);
        if (Instant.now().isBefore(issuance.subscriptionStartAt())) {
            throw new IllegalStateException("청약 시작 시각 전이다: " + issuance.subscriptionStartAt());
        }
        return doTransition(issuance, IssuanceStatus.SUBSCRIBING);
    }

    @Transactional
    public TransitionResult startAllotment(long issuanceId) {
        return transition(issuanceId, IssuanceStatus.ALLOTTING);
    }

    /**
     * 상장 (IS-07). 발행인 계좌에 전량 발행한다.
     * 실제로는 Phase 4 배정 완료 후 상장이다 — Phase 3에서는 ADMIN 수동 경로만 열어둔다.
     */
    @Transactional
    @Auditable(action = "ISSUANCE_LIST", targetType = "ISSUANCE")
    public TransitionResult listIssuance(long issuanceId) {
        Issuance issuance = load(issuanceId);
        UnderlyingAsset asset = assets.findById(issuance.assetId())
                .orElseThrow(() -> new IllegalStateException("기초자산이 없다: " + issuance.assetId()));
        TransitionResult result = doTransition(issuance, IssuanceStatus.LISTED);
        ledger.issue(issuance.tokenSymbol(), OwnerId.of(asset.issuerId()),
                Units.of(issuance.totalUnits()),
                TxRef.of(RefType.ADMIN, "issuance-" + issuanceId));
        events.publishEvent(new com.fracta.issuance.api.TokenListedEvent(issuanceId,
                issuance.tokenSymbol(), issuance.totalUnits(), issuance.unitPrice()));
        return result;
    }

    @Transactional
    public void attachProspectus(long issuanceId, String fileKey) {
        Issuance issuance = load(issuanceId);
        issuance.attachProspectus(fileKey);
        // Phase 8이 AFTER_COMMIT으로 구독한다 — 커밋 전에 소비되지 않는다
        events.publishEvent(new ProspectusUploadedEvent(issuanceId, fileKey));
    }

    @Transactional(readOnly = true)
    public Issuance get(long issuanceId) {
        return load(issuanceId);
    }

    private TransitionResult transition(long issuanceId, IssuanceStatus target) {
        return doTransition(load(issuanceId), target);
    }

    private TransitionResult doTransition(Issuance issuance, IssuanceStatus target) {
        IssuanceStatus from = issuance.status();
        issuance.transitionTo(target);
        return new TransitionResult(issuance.id(), from.name(), target.name());
    }

    private Issuance load(long issuanceId) {
        return issuances.findById(issuanceId)
                .orElseThrow(() -> new IllegalArgumentException("발행 건이 없다: " + issuanceId));
    }
}
