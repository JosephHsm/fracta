package com.fracta.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

/**
 * 관리자 대사 즉시 실행 (운영 개입 + FSD §14 시연).
 *
 * <p>스케줄러의 일일 대사는 JobParameter가 `runDate` 하나라 하루에 한 번만 돈다. 운영에서는
 * 옳지만, <b>훼손이 의심될 때 자정까지 기다릴 수 없다.</b> 이 경로가 그 구멍을 메운다.
 * 실제로 시연 중에 "90초를 기다렸는데 아무 일도 안 일어남"으로 드러난 문제다.
 */
class AdminReconciliationTriggerTest extends IntegrationTestBase {

    @Autowired
    AuthTestSupport auth;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    org.springframework.boot.test.web.client.TestRestTemplate rest;

    private ResponseEntity<String> run(String token) {
        return rest.exchange("/api/v1/admin/batch/reconciliation", HttpMethod.POST,
                new HttpEntity<>(auth.bearer(token)), String.class);
    }

    @Test
    @DisplayName("같은 날 연속 두 번 실행해도 모두 COMPLETED — 하루 1회 제한에 걸리지 않는다")
    void runsRepeatedlyWithinTheSameDay() throws Exception {
        String admin = auth.adminToken();

        for (int attempt = 1; attempt <= 2; attempt++) {
            ResponseEntity<String> response = run(admin);

            assertThat(response.getStatusCode()).as("%d회차", attempt).isEqualTo(HttpStatus.OK);
            var data = objectMapper.readTree(response.getBody()).path("data");
            assertThat(data.path("jobStatus").asText()).as("%d회차 job 상태", attempt)
                    .isEqualTo("COMPLETED");
            assertThat(data.path("exitCode").asText()).isEqualTo("COMPLETED");
            // requestedAt이 식별 파라미터라 매번 새 JobInstance가 된다
            assertThat(data.path("requestedAt").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("일반 투자자 토큰으로는 실행할 수 없다 — 403")
    void rejectsNonAdmin() {
        String investor = auth.signupAndLogin("batch-outsider").token();
        assertThat(run(investor).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
