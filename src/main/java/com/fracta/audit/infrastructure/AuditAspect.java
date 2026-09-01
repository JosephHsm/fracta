package com.fracta.audit.infrastructure;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
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

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

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
        String targetId = resolveTargetId(joinPoint, auditable, result);

        writer.write(new AuditEntry(
                mdcOr("actor", DEFAULT_ACTOR),
                auditable.action(),
                auditable.targetType(),
                targetId,
                mdcOr(RequestIdFilter.CHANNEL_KEY, DEFAULT_CHANNEL),
                beforeState,
                afterState,
                MDC.get(RequestIdFilter.REQUEST_ID_KEY)));
        return result;
    }

    private Object beforeOf(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args.length == 1) {
            return args[0];
        }

        Method method = mostSpecificMethod(joinPoint);
        String[] names = parameterNames.getParameterNames(method);
        Map<String, Object> namedArgs = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = names != null && i < names.length ? names[i] : "arg" + i;
            namedArgs.put(name, args[i]);
        }
        return namedArgs;
    }

    private String resolveTargetId(ProceedingJoinPoint joinPoint, Auditable auditable, Object result) {
        if (auditable.targetId().isBlank()) {
            return null;
        }
        Method method = mostSpecificMethod(joinPoint);
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                null, method, joinPoint.getArgs(), parameterNames);
        context.setVariable("result", result);
        Object value = expressionParser.parseExpression(auditable.targetId()).getValue(context);
        return value == null ? null : value.toString();
    }

    private Method mostSpecificMethod(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        return AopUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());
    }

    private String mdcOr(String key, String fallback) {
        String value = MDC.get(key);
        return value != null ? value : fallback;
    }
}
