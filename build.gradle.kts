plugins {
    java
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.fracta"
version = "0.0.1-SNAPSHOT"
description = "토큰증권 기반 조각투자 발행·유통 플랫폼"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Boot 3.3이 관리하는 1.19.x는 Docker Desktop의 CLI 컨텍스트(npipe dockerDesktopLinuxEngine)를
// 탐지하지 못한다 — 컨텍스트 인식이 들어간 버전으로 오버라이드
extra["testcontainers.version"] = "1.21.3"


dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // 스토리지 MinIO (FSD §4.1) 공식 Java 클라이언트
    implementation("io.minio:minio:8.5.11")

    // Redis 분산락 — 청약 동시성 방식 B (FSD §8.3 명시: Redisson)
    implementation("org.redisson:redisson:3.50.0")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")


    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // 증권사 API 스텁 (FSD 테스트 스택). 스텁은 실제 캡처 응답으로만 만든다
    testImplementation("org.wiremock:wiremock-standalone:3.9.1")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

configurations.all {
    // H2 금지 — 전이 의존성으로도 유입되지 않도록 차단 (advisory lock·pgvector 미지원)
    exclude(group = "com.h2database", module = "h2")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker Engine 29+는 API v1.32를 제거했는데 docker-java(Testcontainers)가 1.32로 고정 호출한다.
    // 1.44는 Docker 25(2024-01)부터 지원되므로 하위 호환 문제 없음.
    systemProperty("api.version", "1.44")
}
