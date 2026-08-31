package com.fracta.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 통합 테스트 기반 클래스. pgvector PG + Redis 컨테이너를 싱글턴으로 띄워
 * 모든 통합 테스트가 재사용한다. H2 금지 — 실제 PostgreSQL만 사용한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("fracta")
            .withUsername("postgres")
            .withPassword("postgres")
            // 컨텍스트 여러 개 × 동시성 테스트 풀(40) — 기본 100으로는 부족하다
            .withCommand("postgres", "-c", "max_connections=300")
            // 슈퍼유저 초기화 스크립트: vector 확장 + 비슈퍼유저 fracta 계정 생성
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/init/init.sql"),
                    "/docker-entrypoint-initdb.d/00-init.sql");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data")
            .withExposedPorts(9000);

    static {
        POSTGRES.start();
        REDIS.start();
        MINIO.start();
    }

    /**
     * 슈퍼유저(postgres) JdbcTemplate — 권한 회수를 우회해야 하는 검증(예: 변조 시뮬레이션) 전용.
     * 애플리케이션 코드 경로에서는 절대 사용하지 않는다.
     */
    protected static org.springframework.jdbc.core.JdbcTemplate adminJdbc() {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "postgres", "postgres");
        return new org.springframework.jdbc.core.JdbcTemplate(ds);
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // 애플리케이션은 비슈퍼유저 fracta로 접속한다 — audit_log 권한 회수가 실효를 갖도록
        registry.add("spring.datasource.username", () -> "fracta");
        registry.add("spring.datasource.password", () -> "fracta");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("minio.endpoint",
                () -> "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000)));
    }
}
