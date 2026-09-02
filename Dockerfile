# FRACTA 백엔드 — 멀티스테이지 빌드.
#
# 빌드 단계에서 테스트는 돌리지 않는다. 테스트는 Testcontainers로 Docker 데몬을 다시 띄우기
# 때문에 이미지 빌드 안에서 돌리면 Docker-in-Docker가 되어 환경에 따라 실패한다.
# 테스트는 `./gradlew test`로 호스트에서 돈다.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# 의존성 레이어를 먼저 굳힌다 — 소스만 바뀌면 이 레이어는 캐시된다
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# 루트로 돌리지 않는다
RUN groupadd --system fracta && useradd --system --gid fracta --create-home fracta
USER fracta

COPY --from=build --chown=fracta:fracta /build/build/libs/*.jar app.jar

EXPOSE 8080

# 컨테이너 메모리 한도를 JVM이 인식하게 한다. -Xmx 고정보다 안전하다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
