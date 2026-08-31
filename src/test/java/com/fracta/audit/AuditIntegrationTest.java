package com.fracta.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.support.AuditDummyService;
import com.fracta.support.AuditDummyService.DummyCommand;
import com.fracta.support.IntegrationTestBase;

class AuditIntegrationTest extends IntegrationTestBase {

    @Autowired
    AuditDummyService dummyService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("@Auditable 메서드 호출 시 audit_log에 before/after가 기록된다")
    void auditableCallRecordsBeforeAndAfter() {
        dummyService.update(new DummyCommand("acct-777", "hello-memo", "pw-1234", "top-secret", "CI_ABC"));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT actor, action, target_type, channel,
                       before_state::text AS before_state, after_state::text AS after_state
                FROM audit_log
                WHERE action = ? AND before_state::text LIKE '%acct-777%'
                ORDER BY id DESC LIMIT 1
                """, "DUMMY_UPDATE");

        assertThat(row.get("action")).isEqualTo("DUMMY_UPDATE");
        assertThat(row.get("target_type")).isEqualTo("DUMMY");
        assertThat(row.get("actor")).isEqualTo("system");
        // 웹 요청 밖(직접 호출)이므로 MDC 채널이 없어 BATCH로 기록된다
        assertThat(row.get("channel")).isEqualTo("BATCH");
        assertThat((String) row.get("before_state")).contains("acct-777").contains("hello-memo");
        assertThat((String) row.get("after_state")).contains("UPDATED");
    }

    @Test
    @DisplayName("password/secret/ci_hash류 필드는 평문으로 저장되지 않는다")
    void sensitiveFieldsAreMasked() {
        dummyService.update(new DummyCommand("acct-888", "memo", "pw-secret-999", "very-secret-value", "CI_HASH_XYZ"));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT before_state::text AS before_state, after_state::text AS after_state
                FROM audit_log
                WHERE action = ? AND before_state::text LIKE '%acct-888%'
                ORDER BY id DESC LIMIT 1
                """, "DUMMY_UPDATE");

        String before = (String) row.get("before_state");
        assertThat(before)
                .doesNotContain("pw-secret-999")
                .doesNotContain("very-secret-value")
                .doesNotContain("CI_HASH_XYZ")
                .contains("***");

        // 반환값의 token 필드도 마스킹된다
        String after = (String) row.get("after_state");
        assertThat(after).doesNotContain("tok-9999").contains("***");
    }

    @Test
    @DisplayName("애플리케이션 DB 유저의 UPDATE/DELETE는 권한 오류로 거부된다 (AU-04)")
    void updateAndDeleteAreDeniedByDbPermission() {
        // PG SQLState 42501(insufficient_privilege) — 원인 예외 메시지에 permission denied가 담긴다
        assertThatThrownBy(() -> jdbcTemplate.execute("UPDATE audit_log SET actor = 'tampered'"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");

        assertThatThrownBy(() -> jdbcTemplate.execute("DELETE FROM audit_log"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }
}
