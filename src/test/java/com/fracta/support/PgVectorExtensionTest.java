package com.fracta.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PgVectorExtensionTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Testcontainers PG에 pgvector 확장이 설치되어 있다")
    void pgvectorExtensionInstalled() {
        String version = jdbcTemplate.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
        assertThat(version).isNotBlank();
    }
}
