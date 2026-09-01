package com.fracta.openapi.sandbox;

import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import com.fracta.openapi.ApiEnv;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 샌드박스 데이터 격리 — <b>PostgreSQL 스키마 분리 + 요청 컨텍스트 라우팅</b> (phase-07 §3.5 권장안).
 *
 * <p>같은 DB에 {@code public}(LIVE)과 {@code sandbox} 두 스키마를 두고, 커넥션의
 * {@code currentSchema}로 갈라진다. 도메인 코드는 어느 쪽인지 알 필요가 없다 — 테이블 이름을
 * 수식하지 않으므로 커넥션이 붙은 스키마에서 자동으로 해석된다.
 *
 * <p>샌드박스 스키마는 같은 마이그레이션 스크립트로 별도 이력을 갖고 따로 올라간다.
 */
@Configuration
public class SandboxDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxDataSourceConfig.class);

    static final String SANDBOX_SCHEMA = "sandbox";

    /**
     * Boot 자동설정이 만든 {@code DataSourceProperties}를 그대로 쓴다.
     * 여기서 같은 타입의 빈을 또 정의하면 자동설정 것과 충돌해 컨텍스트가 뜨지 않는다.
     */
    @Bean
    HikariDataSource liveDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        ds.setPoolName("HikariPool-live");
        return ds;
    }

    @Bean
    HikariDataSource sandboxDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        // 커넥션이 붙는 스키마만 다르다. 테이블 이름을 수식하지 않으므로 도메인 코드는 그대로다.
        ds.setConnectionInitSql("SET search_path TO " + SANDBOX_SCHEMA);
        ds.setPoolName("HikariPool-sandbox");
        return ds;
    }

    /**
     * 요청 컨텍스트로 스키마를 고른다. Flyway·JPA 모두 이 데이터소스를 쓰지만,
     * 스키마 생성과 샌드박스 마이그레이션은 {@link #sandboxSchemaMigrator}가 먼저 끝낸다.
     */
    @Bean
    @Primary
    DataSource routingDataSource(HikariDataSource liveDataSource, HikariDataSource sandboxDataSource) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return SandboxContext.current();
            }
        };
        routing.setTargetDataSources(Map.of(
                ApiEnv.LIVE, liveDataSource,
                ApiEnv.SANDBOX, sandboxDataSource));
        routing.setDefaultTargetDataSource(liveDataSource);
        routing.afterPropertiesSet();
        return routing;
    }

    /**
     * 샌드박스 스키마를 만들고 같은 마이그레이션을 적용한다.
     * Flyway 자동 설정(LIVE)보다 먼저 도는지는 중요하지 않다 — 서로 다른 스키마의 독립 이력이다.
     */
    @Bean
    SandboxSchemaMigrator sandboxSchemaMigrator(HikariDataSource liveDataSource) {
        return new SandboxSchemaMigrator(liveDataSource);
    }

    /** 별도 클래스로 둬서 초기화 순서를 명시적으로 잡는다. */
    public static class SandboxSchemaMigrator {

        private final HikariDataSource liveDataSource;

        public SandboxSchemaMigrator(HikariDataSource liveDataSource) {
            this.liveDataSource = liveDataSource;
        }

        @jakarta.annotation.PostConstruct
        public void migrate() {
            Flyway.configure()
                    .dataSource(liveDataSource)
                    .schemas(SANDBOX_SCHEMA)
                    .defaultSchema(SANDBOX_SCHEMA)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .table("flyway_schema_history")
                    .load()
                    .migrate();
            log.info("샌드박스 스키마 '{}' 마이그레이션 완료 — LIVE 데이터와 완전히 분리된다",
                    SANDBOX_SCHEMA);
        }
    }
}
