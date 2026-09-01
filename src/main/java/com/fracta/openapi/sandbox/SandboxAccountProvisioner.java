package com.fracta.openapi.sandbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SANDBOX API 클라이언트 소유자의 가상 계정을 별도 스키마에 준비한다.
 *
 * <p>인증 주체인 api_client는 LIVE 제어 영역에 두되, 잔고·주문·청약 데이터는 sandbox 스키마의
 * 동일한 owner id를 사용한다. 초기 예치금은 실제 잔고를 복사하지 않고 가상 1억원으로 고정한다.
 */
@Component
public class SandboxAccountProvisioner {

    public static final long INITIAL_CASH = 100_000_000L;

    private final JdbcTemplate jdbc;

    public SandboxAccountProvisioner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void provision(long investorId) {
        int inserted = jdbc.update("""
                INSERT INTO sandbox.investor
                    (id, name, email, password_hash, ci_hash, role, kyc_status,
                     risk_grade, risk_grade_expires_at, cash_balance)
                SELECT id, name, email, password_hash, ci_hash, 'INVESTOR', 'VERIFIED',
                       COALESCE(risk_grade, 3), now() + interval '1 year', ?
                  FROM public.investor
                 WHERE id = ?
                ON CONFLICT (id) DO NOTHING
                """, INITIAL_CASH, investorId);
        if (inserted == 0) {
            Long exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sandbox.investor WHERE id = ?", Long.class, investorId);
            if (exists == null || exists == 0) {
                throw new IllegalArgumentException("샌드박스 소유 투자자가 없다: " + investorId);
            }
        }
    }
}
