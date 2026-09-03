package com.fracta.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fracta.ai.AiUnavailableException;
import com.fracta.common.response.ErrorResponse;

/** 전역 예외 핸들러 — 모든 실패 응답을 FSD §7.1 공통 포맷으로 변환한다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomain(DomainException e) {
        log.warn("domain exception: code={} message={}", e.errorCode().name(), e.getMessage());
        return ResponseEntity.status(e.errorCode().status())
                .body(ErrorResponse.of(e.errorCode().name(), e.getMessage(), e.details()));
    }

    /**
     * 필수 쿼리 파라미터 누락·타입 불일치·본문 파싱 실패는 <b>클라이언트 잘못</b>이다.
     *
     * <p>핸들러가 없으면 마지막 {@code Exception} 핸들러가 잡아 500 INTERNAL_ERROR로 내려간다.
     * 실제로 {@code GET /api/v1/developer/dashboard}를 clientId 없이 부르면 500이 나왔다 —
     * 호출자는 서버가 깨진 줄 알고, 개발자는 로그를 뒤진다. 400으로 정확히 돌려준다.
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (e instanceof MissingServletRequestParameterException missing) {
            details.put(missing.getParameterName(), "필수 파라미터가 없습니다");
        } else if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            details.put(mismatch.getName(), "값의 형식이 올바르지 않습니다");
        }
        // 본문 파싱 실패는 원문을 details에 넣지 않는다 — 요청 본문이 로그·응답에 새면 안 된다
        log.warn("bad request: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.VALID_INVALID_INPUT.name(),
                        ErrorCode.VALID_INVALID_INPUT.defaultMessage(), details));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(err -> details.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.VALID_INVALID_INPUT.name(),
                        ErrorCode.VALID_INVALID_INPUT.defaultMessage(), details));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", "요청한 리소스가 없습니다.", Map.of("path", e.getResourcePath())));
    }

    /**
     * AI 서비스 장애는 503으로 내려보낸다. 500으로 뭉개면 클라이언트가 "서버가 깨졌다"로
     * 읽고 재시도 정책을 잘못 세운다 — AI만 비활성이라는 사실이 응답에 드러나야 한다.
     */
    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAiUnavailable(AiUnavailableException e) {
        log.warn("ai unavailable: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.AI_UNAVAILABLE.status())
                .body(ErrorResponse.of(ErrorCode.AI_UNAVAILABLE.name(),
                        ErrorCode.AI_UNAVAILABLE.defaultMessage(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 내부 오류 상세는 응답에 노출하지 않는다 — 로그로만 남긴다
        log.error("unexpected exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", Map.of()));
    }
}
