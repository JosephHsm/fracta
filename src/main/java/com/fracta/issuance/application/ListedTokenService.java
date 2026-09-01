package com.fracta.issuance.application;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.audit.api.Auditable;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.issuance.api.TokenSuspendedEvent;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.domain.UnderlyingAsset;
import com.fracta.issuance.infrastructure.IssuanceRepository;
import com.fracta.issuance.infrastructure.UnderlyingAssetRepository;

/** ListedTokenPort 구현 — 유통 모듈에 종목 정보를 제공하고 거래 중단을 수행한다. */
@Service
public class ListedTokenService implements ListedTokenPort {

    private static final Logger log = LoggerFactory.getLogger(ListedTokenService.class);

    private final IssuanceRepository issuances;
    private final UnderlyingAssetRepository assets;
    private final ApplicationEventPublisher events;

    public ListedTokenService(IssuanceRepository issuances, UnderlyingAssetRepository assets,
                              ApplicationEventPublisher events) {
        this.issuances = issuances;
        this.assets = assets;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ListedToken> findByTokenSymbol(String tokenSymbol) {
        return issuances.findByTokenSymbol(tokenSymbol).map(this::toListedToken);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ListedToken> listAll() {
        return issuances.findByStatusIn(java.util.List.of(
                        IssuanceStatus.LISTED, IssuanceStatus.SUSPENDED)).stream()
                .map(this::toListedToken)
                .toList();
    }

    @Override
    @Transactional
    @Auditable(action = "TOKEN_SUSPEND", targetType = "TOKEN", targetId = "#p0")
    public void suspend(String tokenSymbol, String reason) {
        Issuance issuance = issuances.findByTokenSymbol(tokenSymbol)
                .orElseThrow(() -> new IllegalArgumentException("종목이 없다: " + tokenSymbol));
        if (issuance.status() == IssuanceStatus.SUSPENDED) {
            return;
        }
        issuance.transitionTo(IssuanceStatus.SUSPENDED);
        log.error("종목 자동 거래 중단: symbol={} 사유={}", tokenSymbol, reason);
        // 커밋 후 유통 모듈이 미체결 주문을 정리한다
        events.publishEvent(new TokenSuspendedEvent(tokenSymbol, reason));
    }

    private ListedToken toListedToken(Issuance issuance) {
        UnderlyingAsset asset = assets.findById(issuance.assetId()).orElse(null);
        return new ListedToken(
                issuance.id(),
                issuance.tokenSymbol(),
                issuance.status().name(),
                issuance.status() == IssuanceStatus.LISTED,
                asset == null ? null : asset.brokerTicker(),
                asset == null ? 1 : asset.splitRatio(),
                issuance.riskGrade());
    }
}
