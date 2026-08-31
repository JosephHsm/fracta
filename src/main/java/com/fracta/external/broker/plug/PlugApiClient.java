package com.fracta.external.broker.plug;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fracta.common.config.BrokerProperties;

/**
 * PLUG REST 호출 공통부.
 *
 * <p>규약 (공식 SDK {@code client.py} 기준):
 * <pre>
 * POST {baseUrl}{path}
 * headers: x-client-id, x-client-secret, authorization: Bearer, content-type: application/json
 * body:    {"Input_0": {...}}
 * </pre>
 *
 * <p>재시도 정책은 코드별로 다르다 — 토큰 무효(401/IGW40043·40044)일 때만 재발급 후 1회 재시도하고,
 * 유량 초과(429/IGW429xx)에는 <b>재시도도 재발급도 하지 않는다</b>. 재발급하면 상황이 악화된다.
 */
@Component
public class PlugApiClient {

    private static final Logger log = LoggerFactory.getLogger(PlugApiClient.class);

    private final BrokerProperties properties;
    private final PlugTokenManager tokenManager;
    private final RateLimiterSelector rateLimiters;
    private final RestClient restClient;

    public PlugApiClient(BrokerProperties properties, PlugTokenManager tokenManager,
                         RateLimiterSelector rateLimiters, RestClient.Builder builder) {
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.rateLimiters = rateLimiters;
        this.restClient = builder.build();
    }

    /** 데이터 조회는 항상 모의 도메인(baseUrl)으로 나간다. 토큰만 실전 도메인에서 받는다. */
    public Map<String, Object> call(String path, Map<String, Object> input) {
        rateLimiters.active().acquire(path);

        Map<String, Object> response = post(path, input, tokenManager.accessToken());
        String gatewayCode = gatewayCode(response);

        if (PlugErrorCodes.isTokenInvalid(gatewayCode)) {
            log.info("토큰 무효({}) — 재발급 후 1회 재시도한다: {}", gatewayCode, path);
            rateLimiters.active().acquire(path);
            response = post(path, input, tokenManager.forceRefresh());
            gatewayCode = gatewayCode(response);
        }

        raiseIfError(path, response, gatewayCode);
        return response;
    }

    private Map<String, Object> post(String path, Map<String, Object> input, String token) {
        try {
            return restClient.post()
                    .uri(properties.baseUrl() + path)
                    .header("x-client-id", properties.appKey())
                    .header("x-client-secret", properties.appSecret())
                    .header("authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("Input_0", input == null ? Map.of() : input))
                    .exchange((request, clientResponse) -> {
                        int status = clientResponse.getStatusCode().value();
                        Map<String, Object> body = readBody(clientResponse);
                        if (status == 401) {
                            // 게이트웨이 코드가 없더라도 토큰 문제로 취급해 재발급 경로를 타게 한다
                            body.putIfAbsent("rsp_cd", "IGW40043");
                        } else if (status == 429) {
                            body.putIfAbsent("rsp_cd", "IGW42902");
                        }
                        return body;
                    });
        } catch (BrokerApiException e) {
            throw e;
        } catch (Exception e) {
            throw BrokerApiException.transport(path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> body = response.bodyTo(Map.class);
            return body == null ? new java.util.HashMap<>() : new java.util.HashMap<>(body);
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    /** 게이트웨이 코드는 rsp_cd 또는 error_code 로 내려온다. */
    private String gatewayCode(Map<String, Object> response) {
        Object code = response.get("rsp_cd");
        if (code == null) {
            code = response.get("error_code");
        }
        return code == null ? null : String.valueOf(code);
    }

    private void raiseIfError(String path, Map<String, Object> response, String code) {
        if (PlugErrorCodes.isRateLimited(code)) {
            throw BrokerApiException.rateLimited(path);
        }
        if (PlugErrorCodes.isTokenInvalid(code)) {
            throw BrokerApiException.unauthorized(path);
        }
        if (!PlugErrorCodes.isSuccess(code)) {
            throw BrokerApiException.business(path, code, String.valueOf(response.get("rsp_msg")));
        }
    }
}
