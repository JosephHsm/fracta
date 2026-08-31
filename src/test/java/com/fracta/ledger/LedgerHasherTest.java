package com.fracta.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxType;
import com.fracta.ledger.infrastructure.LedgerHasher;

class LedgerHasherTest {

    @Test
    @DisplayName("canonical 필드 순서·구분자·null 처리 골든 테스트 — 이 문자열이 바뀌면 전체 체인이 깨진다")
    void canonicalGolden() {
        String canonical = LedgerHasher.canonical(
                1, TxType.ISSUE, "FRACTA-BLD-001",
                null, 7L, 100,
                RefType.SUBSCRIPTION, "sub-42",
                Instant.parse("2026-01-02T03:04:05.678Z"),
                LedgerHasher.GENESIS_PREV_HASH);

        assertThat(canonical).isEqualTo(
                "1|ISSUE|FRACTA-BLD-001||7|100|SUBSCRIPTION|sub-42|2026-01-02T03:04:05.678Z|"
                        + "0".repeat(64));
    }

    @Test
    @DisplayName("createdAt 포맷 안정성 — 밀리초가 0이어도 .000으로 3자리 고정")
    void createdAtFormatIsStable() {
        assertThat(LedgerHasher.CREATED_AT_FORMAT.format(Instant.parse("2026-01-02T03:04:05Z")))
                .isEqualTo("2026-01-02T03:04:05.000Z");
        assertThat(LedgerHasher.CREATED_AT_FORMAT.format(Instant.parse("2026-01-02T03:04:05.678Z")))
                .isEqualTo("2026-01-02T03:04:05.678Z");
        assertThat(LedgerHasher.CREATED_AT_FORMAT.format(Instant.parse("2026-12-31T23:59:59.001Z")))
                .isEqualTo("2026-12-31T23:59:59.001Z");
    }

    @Test
    @DisplayName("refId가 null이면 빈 문자열")
    void nullRefId() {
        String canonical = LedgerHasher.canonical(
                2, TxType.LOCK, "S", 3L, 3L, 5,
                RefType.ADMIN, null,
                Instant.parse("2026-01-01T00:00:00.000Z"), "ab".repeat(32));
        assertThat(canonical).contains("|ADMIN||2026-01-01T00:00:00.000Z|");
    }

    @Test
    @DisplayName("SHA-256 hex 소문자 64자 — 알려진 벡터로 검증")
    void sha256KnownVector() {
        assertThat(LedgerHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(LedgerHasher.sha256Hex("x"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Genesis prev_hash 상수는 '0' 64자")
    void genesisConstant() {
        assertThat(LedgerHasher.GENESIS_PREV_HASH)
                .hasSize(64)
                .matches("0{64}");
    }

    @Test
    @DisplayName("동일 입력은 동일 해시, 한 필드라도 다르면 다른 해시")
    void hashDeterminism() {
        Instant at = Instant.parse("2026-05-05T05:05:05.500Z");
        String h1 = LedgerHasher.hash(10, TxType.TRANSFER, "S", 1L, 2L, 30,
                RefType.EXECUTION, "e-1", at, "cd".repeat(32));
        String h2 = LedgerHasher.hash(10, TxType.TRANSFER, "S", 1L, 2L, 30,
                RefType.EXECUTION, "e-1", at, "cd".repeat(32));
        String h3 = LedgerHasher.hash(10, TxType.TRANSFER, "S", 1L, 2L, 31,
                RefType.EXECUTION, "e-1", at, "cd".repeat(32));

        assertThat(h1).isEqualTo(h2).isNotEqualTo(h3);
    }
}
