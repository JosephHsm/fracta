package com.fracta.audit.infrastructure;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.fracta.audit.api.AuditEntry;
import com.fracta.audit.api.Auditable;
import com.fracta.common.logging.RequestIdFilter;

/**
 * {@link Auditable} 메서드를 가로채 before(인자)/after(반환값)를 마스킹 후 audit_log에 기록한다.
 * 채널은 MDC에서 판별한다 — 웹 요청이면 필터가 WEB/API를 넣어 두고, 없으면 BATCH로 본다.
 */
@Aspect
@Component
public class AuditAspect {

    private static final String DEFAULT_ACTOR = "system";
    private static final String DEFAULT_CHANNEL = "BATCH";

    private final AuditLogWriter writer;
    private final SensitiveFieldMasker masker;

    public AuditAspect(AuditLogWriter writer, SensitiveFieldMasker masker) {
        this.writer = writer;
        this.masker = masker;
    }

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        String beforeState = masker.maskToJson(beforeOf(joinPoint));
        Object result = joinPoint.proceed();
        String afterState = masker.maskToJson(result);

        writer.write(new AuditEntry(
                mdcOr("actor", DEFAULT_ACTOR),
                auditable.action(),
                auditable.targetType(),
                null,
                mdcOr(RequestIdFilter.CHANNEL_KEY, DEFAULT_CHANNEL),
                beforeState,
                afterState,
                MDC.get(RequestIdFilter.REQUEST_ID_KEY)));
        return result;
    }

    private Object beforeOf(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        return args.length == 1 ? args[0] : args;
    }

    private String mdcOr(String key, String fallback) {
        String value = MDC.get(key);
        return value != null ? value : fallback;
    }
}
