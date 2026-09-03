package com.fracta.openapi.gateway;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;
import com.fracta.common.ratelimit.SlidingWindowCounter;
import com.fracta.common.response.ErrorResponse;
import com.fracta.openapi.ApiEnv;
import com.fracta.openapi.auth.ApiClient;
import com.fracta.openapi.auth.ApiClientService;
import com.fracta.openapi.auth.ApiScope;
import com.fracta.openapi.auth.OpenApiExceptions;
import com.fracta.openapi.idempotency.IdempotencyService;
import com.fracta.openapi.log.ApiCallLogger;
import com.fracta.openapi.sandbox.SandboxContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 오픈 API 게이트웨이 — 인증 · 환경 격리 · 쿼터 · 멱등성 · 호출 로그를 한 곳에서 처리한다.
 *
 * <p>순서가 중요하다. 인증 → 환경 판별(스키마 라우팅) → 쿼터 → 멱등성 순이며,
 * 쿼터 헤더는 <b>성공 응답에도</b> 붙는다 (phase-07 §흔한 실수 4).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OpenApiGatewayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiGatewayFilter.class);

    public static final String LIVE_PREFIX = "/open/v1";
    public static final String SANDBOX_PREFIX = "/open/sandbox/v1";
    private static final String TOKEN_PATH_SUFFIX = "/oauth/token";

    private final JwtDecoder jwtDecoder;
    private final ApiClientService clientService;
    private final SlidingWindowCounter counter;
    private final IdempotencyService idempotency;
    private final ApiCallLogger callLogger;
    private final ObjectMapper objectMapper;
    private final ScopeResolver scopeResolver;

    public OpenApiGatewayFilter(@Qualifier("openApiJwtDecoder") JwtDecoder jwtDecoder,
                                ApiClientService clientService, SlidingWindowCounter counter,
                                IdempotencyService idempotency, ApiCallLogger callLogger,
                                ObjectMapper objectMapper, ScopeResolver scopeResolver) {
        this.jwtDecoder = jwtDecoder;
        this.clientService = clientService;
        this.counter = counter;
        this.idempotency = idempotency;
        this.callLogger = callLogger;
        this.objectMapper = objectMapper;
        this.scopeResolver = scopeResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        boolean openApi = uri.startsWith(LIVE_PREFIX) || uri.startsWith(SANDBOX_PREFIX);
        // 토큰 발급은 아직 토큰이 없으므로 게이트웨이를 거치지 않는다
        return !openApi || uri.endsWith(TOKEN_PATH_SUFFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        CachedBodyRequestWrapper cachedRequest = new CachedBodyRequestWrapper(request);
        String idempotencyKey = request.getHeader("Idempotency-Key");
        String clientId = null;
        int status = HttpServletResponse.SC_OK;

        try {
            ApiClient client = authenticate(cachedRequest);
            clientId = client.clientId();

            ApiEnv pathEnv = pathEnv(cachedRequest.getRequestURI());
            requireMatchingEnv(client, pathEnv);
            SandboxContext.set(pathEnv);

            OpenApiContext.set(new OpenApiContext(client.clientId(), client.ownerInvestorId(),
                    ApiScope.parse(client.scopes()), client.env()));

            enforceQuota(client, response);
            status = dispatch(cachedRequest, response, chain, client, idempotencyKey);
        } catch (DomainException e) {
            status = e.errorCode().status().value();
            writeError(response, e);
        } catch (Exception e) {
            log.error("오픈 API 처리 중 예상 밖 오류", e);
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            writeError(response, ErrorCode.BROKER_CALL_FAILED.name(), "서버 내부 오류가 발생했습니다.",
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            OpenApiContext.clear();
            SandboxContext.clear();
            callLogger.record(clientId, cachedRequest.getRequestURI(), cachedRequest.getMethod(),
                    status, Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                    idempotencyKey, cachedRequest.bodyAsString());
        }
    }

    // ── 인증 ────────────────────────────────────────────────

    private ApiClient authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new InvalidTokenException();
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(7));
        } catch (Exception e) {
            // 401 만 남기면 원인을 알 수 없다. 토큰 값은 남기지 않고 사유만 기록한다
            log.warn("오픈 API 토큰 검증 실패: {}", e.getMessage());
            throw new InvalidTokenException();
        }
        ApiClient client = clientService.require(jwt.getSubject());
        if (!client.active()) {
            throw new OpenApiExceptions.InvalidClientException(client.clientId());
        }
        return client;
    }

    /** 만료·위조·웹앱 토큰 유입 모두 여기로 모인다. */
    static class InvalidTokenException extends DomainException {
        InvalidTokenException() {
            super(ErrorCode.AUTH_INVALID_TOKEN);
        }
    }

    // ── 환경 격리 ───────────────────────────────────────────

    private ApiEnv pathEnv(String uri) {
        return uri.startsWith(SANDBOX_PREFIX) ? ApiEnv.SANDBOX : ApiEnv.LIVE;
    }

    private void requireMatchingEnv(ApiClient client, ApiEnv pathEnv) {
        if (client.env() != pathEnv) {
            throw new OpenApiExceptions.EnvMismatchException(client.env().name(), pathEnv.name());
        }
    }

    // ── 쿼터 ────────────────────────────────────────────────

    private void enforceQuota(ApiClient client, HttpServletResponse response) {
        var perSec = counter.tryAcquire("quota:sec:" + client.clientId(),
                client.rateLimitPerSec(), Duration.ofSeconds(1));
        // 성공 응답에도 헤더를 붙인다
        response.setHeader("X-RateLimit-Limit", String.valueOf(perSec.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(perSec.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(perSec.resetEpochSeconds()));
        if (!perSec.allowed()) {
            response.setHeader("Retry-After", "1");
            throw new RateLimitExceededException("초당 호출 한도를 초과했습니다.");
        }

        var perDay = counter.tryAcquire("quota:day:" + client.clientId(),
                client.rateLimitPerDay(), Duration.ofDays(1));
        if (!perDay.allowed()) {
            response.setHeader("Retry-After", "60");
            throw new RateLimitExceededException("일일 호출 한도를 초과했습니다.");
        }
    }

    static class RateLimitExceededException extends DomainException {
        RateLimitExceededException(String message) {
            super(ErrorCode.RATE_LIMIT_EXCEEDED, message, java.util.Map.of());
        }
    }

    // ── 멱등성 ──────────────────────────────────────────────

    private int dispatch(CachedBodyRequestWrapper request, HttpServletResponse response,
                         FilterChain chain, ApiClient client, String idempotencyKey)
            throws ServletException, IOException {
        if (!scopeResolver.requiresIdempotency(request)) {
            chain.doFilter(request, response);
            return response.getStatus();
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new OpenApiExceptions.IdempotencyKeyRequiredException();
        }
        if (idempotencyKey.length() > 100) {
            throw new OpenApiExceptions.InvalidIdempotencyKeyException(idempotencyKey.length());
        }

        String rawBody = request.bodyAsString();
        Optional<IdempotencyService.StoredResponse> replay =
                idempotency.begin(client.clientId(), idempotencyKey,
                        request.getMethod(), request.getRequestURI(), rawBody);
        if (replay.isPresent()) {
            response.setStatus(replay.get().status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("X-Idempotent-Replay", "true");
            response.getWriter().write(replay.get().body());
            return replay.get().status();
        }

        CachedBodyResponseWrapper cachedResponse = new CachedBodyResponseWrapper(response);
        try {
            chain.doFilter(request, cachedResponse);
            int status = cachedResponse.getStatus();
            if (status < 400) {
                idempotency.complete(client.clientId(), idempotencyKey,
                        request.getMethod(), request.getRequestURI(), rawBody,
                        status, cachedResponse.bodyAsString());
            } else {
                // 실패 응답은 재생하지 않는다 — 재시도할 수 있어야 한다
                idempotency.abort(client.clientId(), idempotencyKey);
            }
            cachedResponse.flushToClient();
            return status;
        } catch (Exception e) {
            // 처리 중 예외 → PROCESSING 키를 반드시 지운다. 안 지우면 24시간 재시도 불가
            idempotency.abort(client.clientId(), idempotencyKey);
            throw e;
        }
    }

    // ── 오류 응답 ───────────────────────────────────────────

    private void writeError(HttpServletResponse response, DomainException e) throws IOException {
        writeError(response, e.errorCode().name(), e.getMessage(), e.errorCode().status().value());
    }

    /** 쿼터·재시도 헤더는 남기고 본문만 갈아끼운다 — reset() 이 헤더까지 지우기 때문이다. */
    private static final String[] PRESERVED_HEADERS = {
            "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After"};

    private void writeError(HttpServletResponse response, String code, String message, int status)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        java.util.Map<String, String> preserved = new java.util.LinkedHashMap<>();
        for (String name : PRESERVED_HEADERS) {
            String value = response.getHeader(name);
            if (value != null) {
                preserved.put(name, value);
            }
        }
        response.reset();
        preserved.forEach(response::setHeader);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorResponse.of(code, message, java.util.Map.of())));
    }
}
