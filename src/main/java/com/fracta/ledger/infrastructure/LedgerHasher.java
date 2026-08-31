package com.fracta.ledger.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxType;

/**
 * 해시체인 정규화·해시 계산 — 순수 함수 (FSD §8.2).
 *
 * <p>canonical 필드 순서와 createdAt 포맷은 절대 변경 금지. 포맷이 흔들리면
 * 기존 체인 재검증이 전부 실패한다. 안정성을 위해 밀리초 3자리를 항상 출력하는
 * 고정 포맷터를 쓴다 ({@code Instant.toString()}은 밀리초가 0이면 생략해 불안정).
 */
public final class LedgerHasher {

    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    /** ISO-8601 UTC, 밀리초 3자리 고정. 변경 금지. */
    public static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private LedgerHasher() {
    }

    public static String canonical(long seq, TxType txType, String tokenSymbol,
                                   Long fromOwnerId, Long toOwnerId, long units,
                                   RefType refType, String refId,
                                   Instant createdAt, String prevHash) {
        return String.join("|",
                String.valueOf(seq),
                txType.name(),
                tokenSymbol,
                nullSafe(fromOwnerId),
                nullSafe(toOwnerId),
                String.valueOf(units),
                refType.name(),
                refId == null ? "" : refId,
                CREATED_AT_FORMAT.format(createdAt),
                prevHash);
    }

    public static String hash(long seq, TxType txType, String tokenSymbol,
                              Long fromOwnerId, Long toOwnerId, long units,
                              RefType refType, String refId,
                              Instant createdAt, String prevHash) {
        return sha256Hex(canonical(seq, txType, tokenSymbol, fromOwnerId, toOwnerId,
                units, refType, refId, createdAt, prevHash));
    }

    /** SHA-256, hex 소문자 64자. */
    public static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없다", e);
        }
    }

    private static String nullSafe(Long value) {
        return value == null ? "" : String.valueOf(value);
    }
}
