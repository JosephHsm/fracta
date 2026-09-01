package com.fracta.openapi.gateway;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.fracta.openapi.auth.ApiScope;
import com.fracta.openapi.auth.OpenApiExceptions;

/**
 * Scope 검증 (OA-03) — 메서드 레벨. 컨트롤러가 호출되기 직전에 막는다.
 * 필터가 아니라 여기서 보는 이유는 엔드포인트별 요구 scope 가 메서드에 선언돼 있기 때문이다.
 */
@Aspect
@Component
public class ScopeCheckAspect {

    @Around("@annotation(requiredScope)")
    public Object check(ProceedingJoinPoint joinPoint, RequiredScope requiredScope) throws Throwable {
        OpenApiContext context = OpenApiContext.current();
        ApiScope required = requiredScope.value();
        if (!context.hasScope(required)) {
            throw new OpenApiExceptions.ScopeDeniedException(required.value(),
                    ApiScope.join(context.scopes()));
        }
        return joinPoint.proceed();
    }
}
