package com.fracta.common.logging;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 인증된 요청의 주체를 MDC "actor"에 넣는다 — 감사 로그 AOP가 행위자로 기록한다.
 * Spring Security 필터 체인 이후에 실행된다 (@Component 기본 순서).
 */
@Component
public class ActorMdcFilter extends OncePerRequestFilter {

    public static final String ACTOR_KEY = "actor";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean set = false;
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            MDC.put(ACTOR_KEY, auth.getName());
            set = true;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (set) {
                MDC.remove(ACTOR_KEY);
            }
        }
    }
}
