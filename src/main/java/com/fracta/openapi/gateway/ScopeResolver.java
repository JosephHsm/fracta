package com.fracta.openapi.gateway;

import java.lang.reflect.Method;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청이 어느 핸들러로 갈지 미리 알아내 {@link RequiredScope}를 읽는다.
 * 필터 단계에서 메서드 레벨 애노테이션을 봐야 하기 때문이다.
 */
@Component
public class ScopeResolver {

    private final java.util.List<HandlerMapping> handlerMappings;

    public ScopeResolver(java.util.List<HandlerMapping> handlerMappings) {
        this.handlerMappings = handlerMappings;
    }

    public Optional<RequiredScope> resolve(HttpServletRequest request) {
        for (HandlerMapping mapping : handlerMappings) {
            try {
                HandlerExecutionChain chain = mapping.getHandler(request);
                if (chain != null && chain.getHandler() instanceof HandlerMethod handlerMethod) {
                    Method method = handlerMethod.getMethod();
                    RequiredScope annotation = method.getAnnotation(RequiredScope.class);
                    return Optional.ofNullable(annotation);
                }
            } catch (Exception e) {
                // 이 매핑이 처리하지 않는 요청이다 — 다음 매핑을 본다
            }
        }
        return Optional.empty();
    }

    public boolean requiresIdempotency(HttpServletRequest request) {
        return resolve(request).map(RequiredScope::idempotent).orElse(false);
    }
}
