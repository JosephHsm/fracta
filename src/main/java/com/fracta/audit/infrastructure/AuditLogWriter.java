package com.fracta.audit.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fracta.audit.api.AuditEntry;

/** audit_log INSERT 전용. UPDATE/DELETE는 DB 권한 레벨에서 차단되어 있다 (FSD AU-04). */
@Component
public class AuditLogWriter {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void write(AuditEntry entry) {
        jdbcTemplate.update("""
                        INSERT INTO audit_log
                            (actor, action, target_type, target_id, channel, before_state, after_state, request_id)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                        """,
                entry.actor(), entry.action(), entry.targetType(), entry.targetId(),
                entry.channel(), entry.beforeState(), entry.afterState(), entry.requestId());
    }
}
