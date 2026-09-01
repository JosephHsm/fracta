package com.fracta.batch.application;

import java.time.Instant;
import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 90일 경과 API 호출 로그를 원본 ID 그대로 이동한다. 삽입과 삭제는 한 트랜잭션이다. */
@Service
public class ApiLogArchiveService {

    private final JdbcTemplate jdbc;

    public ApiLogArchiveService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int archiveBefore(Instant cutoff, long jobExecutionId) {
        Timestamp cutoffTimestamp = Timestamp.from(cutoff);
        int archived = jdbc.update("""
                INSERT INTO api_call_log_archive
                    (original_id, client_id, endpoint, method, status_code, latency_ms,
                     idempotency_key, body_hash, called_at, job_execution_id)
                SELECT id, client_id, endpoint, method, status_code, latency_ms,
                       idempotency_key, body_hash, called_at, ?
                FROM api_call_log
                WHERE called_at < ?
                ON CONFLICT (original_id) DO NOTHING
                """, jobExecutionId, cutoffTimestamp);

        jdbc.update("""
                DELETE FROM api_call_log source
                WHERE source.called_at < ?
                  AND EXISTS (SELECT 1 FROM api_call_log_archive archive
                              WHERE archive.original_id = source.id)
                """, cutoffTimestamp);
        return archived;
    }
}
