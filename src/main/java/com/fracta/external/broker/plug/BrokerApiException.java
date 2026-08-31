package com.fracta.external.broker.plug;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/**
 * 증권사 API 호출 실패.
 *
 * <p>PLUG의 실제 오류 체계 (공식 문서 기준, {@code IGW*} 코드는 존재하지 않는다):
 * <ul>
 *   <li>HTTP 401 — 토큰 무효. <b>이때만</b> 재발급 후 1회 재시도</li>
 *   <li>HTTP 429 — 유량 초과. 재시도·재발급 모두 금지, 호출 간격을 늘린다</li>
 *   <li>HTTP 200 + {@code rsp_cd}가 성공 코드가 아님 — 업무 오류</li>
 * </ul>
 */
public class BrokerApiException extends DomainException {

    public enum Category { AUTH, RATE_LIMIT, BUSINESS, TRANSPORT }

    private final Category category;

    private BrokerApiException(ErrorCode code, Category category, String message,
                               Map<String, Object> details, Throwable cause) {
        super(code, message, details, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /** 유량 초과 — 재시도하면 더 나빠진다. */
    public static BrokerApiException rateLimited(String path) {
        return new BrokerApiException(ErrorCode.RATE_LIMIT_EXCEEDED, Category.RATE_LIMIT,
                "증권사 API 유량 초과 (HTTP 429): " + path, Map.of("path", path), null);
    }

    public static BrokerApiException unauthorized(String path) {
        return new BrokerApiException(ErrorCode.BROKER_AUTH_FAILED, Category.AUTH,
                "증권사 API 인증 실패 (HTTP 401): " + path, Map.of("path", path), null);
    }

    public static BrokerApiException business(String path, String rspCd, String rspMsg) {
        return new BrokerApiException(ErrorCode.BROKER_CALL_FAILED, Category.BUSINESS,
                "증권사 API 업무 오류 [%s] %s".formatted(rspCd, rspMsg),
                Map.of("path", path, "rspCd", rspCd, "rspMsg", rspMsg == null ? "" : rspMsg), null);
    }

    public static BrokerApiException transport(String path, Throwable cause) {
        return new BrokerApiException(ErrorCode.BROKER_CALL_FAILED, Category.TRANSPORT,
                "증권사 API 통신 오류: " + path, Map.of("path", path), cause);
    }
}
