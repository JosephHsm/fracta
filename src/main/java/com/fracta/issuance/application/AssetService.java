package com.fracta.issuance.application;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.issuance.domain.UnderlyingAsset;
import com.fracta.issuance.infrastructure.UnderlyingAssetRepository;

/** 기초자산 등록 (IS-01). */
@Service
public class AssetService {

    private static final Pattern ASSET_CODE = Pattern.compile("^[A-Z]{4}$");

    private final UnderlyingAssetRepository assets;

    public AssetService(UnderlyingAssetRepository assets) {
        this.assets = assets;
    }

    @Transactional
    public UnderlyingAsset create(long issuerId, String name, UnderlyingAsset.AssetType type,
                                  String assetCode, String brokerTicker, long splitRatio, String description) {
        if (splitRatio <= 0) {
            throw new IllegalArgumentException("분할비율은 1 이상이어야 한다: " + splitRatio);
        }
        String code = assetCode == null || assetCode.isBlank() ? generateCode() : assetCode.toUpperCase();
        if (!ASSET_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("자산코드는 영문 대문자 4자여야 한다: " + code);
        }
        return assets.save(new UnderlyingAsset(name, type, code, brokerTicker, splitRatio, description, issuerId));
    }

    private String generateCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder sb = new StringBuilder(4);
            for (int i = 0; i < 4; i++) {
                sb.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
            }
            String code = sb.toString();
            if (!assets.existsByAssetCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("자산코드 자동 생성 실패");
    }
}
