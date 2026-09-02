package com.fracta.openapi.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.audit.api.Auditable;
import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;
import com.fracta.openapi.ApiEnv;
import com.fracta.openapi.auth.ApiClient;
import com.fracta.openapi.auth.ApiClientRepository;
import com.fracta.openapi.auth.ApiClientService;
import com.fracta.openapi.auth.ApiScope;
import com.fracta.openapi.log.ApiCallLog;
import com.fracta.openapi.log.ApiCallLogRepository;
import com.fracta.openapi.webhook.WebhookDelivery;
import com.fracta.openapi.webhook.WebhookDeliveryRepository;
import com.fracta.openapi.webhook.WebhookDispatcher;
import com.fracta.openapi.webhook.WebhookEndpoint;
import com.fracta.openapi.webhook.WebhookEndpointRepository;
import com.fracta.openapi.webhook.WebhookEvent;

/** 개발자 포털의 앱·통계·호출 로그·웹훅 유스케이스. 트랜잭션 경계는 이 레이어에 둔다. */
@Service
public class DeveloperPortalService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int LOG_RESULT_LIMIT = 200;

    public record ClientView(String clientId, String name, Set<ApiScope> scopes, ApiEnv env,
                             int rateLimitPerSec, int rateLimitPerDay, boolean active,
                             Instant createdAt) {
    }

    /** clientSecret은 생성·재발급 응답에만 존재한다. 조회 모델과 의도적으로 분리한다. */
    public record IssuedClientView(String clientId, String clientSecret, Set<ApiScope> scopes,
                                   ApiEnv env) {
    }

    public record QuotaView(int perSecondLimit, int perDayLimit, long usedToday,
                            long remainingToday) {
    }

    public record DailyUsageView(LocalDate date, long calls, long errors, long averageLatencyMs) {
    }

    public record DashboardView(long callsToday, long callsLastSevenDays,
                                BigDecimal errorRatePercent, long averageLatencyMs,
                                QuotaView quota, List<DailyUsageView> dailyUsage) {
    }

    public record CallLogView(Long id, String clientId, String endpoint, String method,
                              int statusCode, long latencyMs, String idempotencyKey,
                              Instant calledAt) {
    }

    public record WebhookView(Long webhookId, String clientId, String url, Set<String> events,
                              boolean active, Instant createdAt) {
    }

    /** webhookSecret도 등록 직후 응답 한 번에만 존재한다. */
    public record IssuedWebhookView(Long webhookId, String url, Set<String> events,
                                    String webhookSecret) {
    }

    public record WebhookDeliveryView(Long deliveryId, Long webhookId, String eventType,
                                      int attempts, WebhookDelivery.Status status,
                                      String lastError, Instant createdAt, Instant deliveredAt) {
    }

    private final ApiClientRepository clients;
    private final ApiClientService clientService;
    private final ApiCallLogRepository callLogs;
    private final WebhookEndpointRepository webhookEndpoints;
    private final WebhookDeliveryRepository webhookDeliveries;
    private final WebhookDispatcher webhookDispatcher;

    public DeveloperPortalService(ApiClientRepository clients, ApiClientService clientService,
                                  ApiCallLogRepository callLogs,
                                  WebhookEndpointRepository webhookEndpoints,
                                  WebhookDeliveryRepository webhookDeliveries,
                                  WebhookDispatcher webhookDispatcher) {
        this.clients = clients;
        this.clientService = clientService;
        this.callLogs = callLogs;
        this.webhookEndpoints = webhookEndpoints;
        this.webhookDeliveries = webhookDeliveries;
        this.webhookDispatcher = webhookDispatcher;
    }

    @Transactional(readOnly = true)
    public List<ClientView> clients(long ownerInvestorId) {
        return clients.findByOwnerInvestorId(ownerInvestorId).stream()
                .map(DeveloperPortalService::toClientView)
                .toList();
    }

    @Transactional
    public IssuedClientView registerClient(String name, long ownerInvestorId,
                                           Set<ApiScope> scopes, ApiEnv env) {
        var issued = clientService.register(name, ownerInvestorId, scopes, env, 10, 10_000);
        return issued(issued);
    }

    @Transactional
    public IssuedClientView rotateSecret(String clientId, long ownerInvestorId) {
        return issued(clientService.rotateOwnedSecret(clientId, ownerInvestorId));
    }

    @Transactional
    public ClientView updateScopes(String clientId, long ownerInvestorId, Set<ApiScope> scopes) {
        clientService.updateScopes(clientId, ownerInvestorId, scopes);
        return toClientView(requireOwnedClient(clientId, ownerInvestorId));
    }

    @Transactional(readOnly = true)
    public DashboardView dashboard(String clientId, long ownerInvestorId) {
        ApiClient client = requireOwnedClient(clientId, ownerInvestorId);
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(SEOUL);
        Instant todayStart = today.atStartOfDay(SEOUL).toInstant();
        Instant sevenDaysStart = today.minusDays(6).atStartOfDay(SEOUL).toInstant();

        List<ApiCallLog> logs = callLogs.findTimeline(clientId, sevenDaysStart);
        long callsToday = logs.stream().filter(log -> !log.calledAt().isBefore(todayStart)).count();
        long errors = logs.stream().filter(log -> log.statusCode() >= 400).count();
        long latencyTotal = logs.stream().mapToLong(ApiCallLog::latencyMs).sum();
        long averageLatency = logs.isEmpty() ? 0 : latencyTotal / logs.size();
        BigDecimal errorRate = logs.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(errors)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(logs.size()), 2, RoundingMode.HALF_UP);

        Map<LocalDate, MutableUsage> byDate = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            byDate.put(today.minusDays(i), new MutableUsage());
        }
        for (ApiCallLog log : logs) {
            LocalDate day = log.calledAt().atZone(SEOUL).toLocalDate();
            MutableUsage usage = byDate.get(day);
            if (usage != null) {
                usage.calls++;
                usage.latencyTotal += log.latencyMs();
                if (log.statusCode() >= 400) usage.errors++;
            }
        }
        List<DailyUsageView> timeline = byDate.entrySet().stream()
                .map(entry -> new DailyUsageView(entry.getKey(), entry.getValue().calls,
                        entry.getValue().errors, entry.getValue().averageLatency()))
                .toList();
        long remaining = Math.max(0L, (long) client.rateLimitPerDay() - callsToday);
        return new DashboardView(callsToday, logs.size(), errorRate, averageLatency,
                new QuotaView(client.rateLimitPerSec(), client.rateLimitPerDay(), callsToday, remaining),
                timeline);
    }

    @Transactional(readOnly = true)
    public List<CallLogView> searchLogs(String clientId, long ownerInvestorId, String endpoint,
                                        Integer statusCode, Instant from, Instant to) {
        requireOwnedClient(clientId, ownerInvestorId);
        Instant safeTo = to == null ? Instant.now() : to;
        Instant safeFrom = from == null ? safeTo.minus(30, ChronoUnit.DAYS) : from;
        if (safeFrom.isAfter(safeTo)) {
            throw new PortalValidationException("시작 시각은 종료 시각보다 늦을 수 없다");
        }
        String normalizedEndpoint = endpoint == null || endpoint.isBlank() ? "" : endpoint.trim();
        int normalizedStatus = statusCode == null ? -1 : statusCode;
        return callLogs.search(clientId, normalizedEndpoint, normalizedStatus, safeFrom, safeTo,
                        PageRequest.of(0, LOG_RESULT_LIMIT)).stream()
                .map(log -> new CallLogView(log.id(), log.clientId(), log.endpoint(), log.method(),
                        log.statusCode(), log.latencyMs(), log.idempotencyKey(), log.calledAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WebhookView> webhooks(String clientId, long ownerInvestorId) {
        requireOwnedClient(clientId, ownerInvestorId);
        return webhookEndpoints.findByClientIdOrderByIdDesc(clientId).stream()
                .map(DeveloperPortalService::toWebhookView)
                .toList();
    }

    @Transactional
    @Auditable(action = "WEBHOOK_REGISTER", targetType = "WEBHOOK_ENDPOINT",
            targetId = "#result.webhookId()")
    public IssuedWebhookView registerWebhook(String clientId, long ownerInvestorId, String url,
                                             Set<String> requestedEvents) {
        requireOwnedClient(clientId, ownerInvestorId);
        Set<WebhookEvent> events = requestedEvents.stream()
                .map(WebhookEvent::of)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        String secret = "whsec_" + randomToken(32);
        WebhookEndpoint endpoint = webhookEndpoints.save(new WebhookEndpoint(
                clientId, ownerInvestorId, url, secret, events));
        return new IssuedWebhookView(endpoint.id(), endpoint.url(), eventValues(events), secret);
    }

    @Transactional(readOnly = true)
    public List<WebhookDeliveryView> deliveries(long webhookId, long ownerInvestorId) {
        WebhookEndpoint endpoint = requireOwnedWebhook(webhookId, ownerInvestorId);
        return webhookDeliveries.findByEndpointIdOrderByIdDesc(endpoint.id()).stream()
                .limit(LOG_RESULT_LIMIT)
                .map(DeveloperPortalService::toDeliveryView)
                .toList();
    }

    @Transactional
    @Auditable(action = "WEBHOOK_REDELIVER", targetType = "WEBHOOK_DELIVERY", targetId = "#p0")
    public WebhookDeliveryView redeliver(long deliveryId, long ownerInvestorId) {
        WebhookDelivery delivery = webhookDeliveries.findById(deliveryId)
                .orElseThrow(() -> new PortalNotFoundException("웹훅 발송 이력이 없다"));
        requireOwnedWebhook(delivery.endpointId(), ownerInvestorId);
        try {
            return toDeliveryView(webhookDispatcher.redeliver(deliveryId));
        } catch (IllegalStateException e) {
            throw new PortalValidationException("실패한 웹훅만 재발송할 수 있다");
        }
    }

    private ApiClient requireOwnedClient(String clientId, long ownerInvestorId) {
        ApiClient client = clients.findByClientId(clientId)
                .orElseThrow(() -> new PortalNotFoundException("등록된 앱이 없다"));
        if (client.ownerInvestorId() != ownerInvestorId) throw new PortalForbiddenException();
        return client;
    }

    private WebhookEndpoint requireOwnedWebhook(long webhookId, long ownerInvestorId) {
        WebhookEndpoint endpoint = webhookEndpoints.findById(webhookId)
                .orElseThrow(() -> new PortalNotFoundException("등록된 웹훅이 없다"));
        if (endpoint.ownerInvestorId() != ownerInvestorId) throw new PortalForbiddenException();
        return endpoint;
    }

    private static ClientView toClientView(ApiClient client) {
        return new ClientView(client.clientId(), client.name(), ApiScope.parse(client.scopes()),
                client.env(), client.rateLimitPerSec(), client.rateLimitPerDay(), client.active(),
                client.createdAt());
    }

    private static IssuedClientView issued(ApiClientService.RegisteredClient issued) {
        return new IssuedClientView(issued.clientId(), issued.clientSecret(),
                ApiScope.parse(issued.scopes()), issued.env());
    }

    private static WebhookView toWebhookView(WebhookEndpoint endpoint) {
        Set<String> events = Set.of(endpoint.events().split(","));
        return new WebhookView(endpoint.id(), endpoint.clientId(), endpoint.url(), events,
                endpoint.active(), endpoint.createdAt());
    }

    private static Set<String> eventValues(Set<WebhookEvent> events) {
        return events.stream().map(WebhookEvent::value)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static WebhookDeliveryView toDeliveryView(WebhookDelivery delivery) {
        return new WebhookDeliveryView(delivery.id(), delivery.endpointId(), delivery.eventType(),
                delivery.attempts(), delivery.status(), delivery.lastError(), delivery.createdAt(),
                delivery.deliveredAt());
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static final class MutableUsage {
        private long calls;
        private long errors;
        private long latencyTotal;

        private long averageLatency() {
            return calls == 0 ? 0 : latencyTotal / calls;
        }
    }

    public static class PortalForbiddenException extends DomainException {
        public PortalForbiddenException() {
            super(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    public static class PortalNotFoundException extends DomainException {
        public PortalNotFoundException(String message) {
            super(ErrorCode.VALID_INVALID_INPUT, message);
        }
    }

    public static class PortalValidationException extends DomainException {
        public PortalValidationException(String message) {
            super(ErrorCode.VALID_INVALID_INPUT, message);
        }
    }
}
