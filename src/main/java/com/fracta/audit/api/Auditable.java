package com.fracta.audit.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 상태 변경 메서드에 붙이면 AOP가 before(인자)/after(반환값)를 마스킹 후 audit_log에 기록한다.
 * 실제 부착 대상은 Phase 3부터.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 행위 식별자. 예: SUBSCRIPTION_APPLY */
    String action();

    /** 대상 타입. 예: SUBSCRIPTION */
    String targetType();

    /**
     * 대상 식별자를 구하는 SpEL. 메서드 인자는 {@code #p0}, {@code #p1}, 반환값은
     * {@code #result} 로 참조한다.
     */
    String targetId() default "";
}
