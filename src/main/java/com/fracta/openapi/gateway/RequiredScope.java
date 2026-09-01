package com.fracta.openapi.gateway;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.fracta.openapi.auth.ApiScope;

/** 엔드포인트가 요구하는 scope. 게이트웨이 필터가 메서드 레벨에서 검증한다 (OA-03). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiredScope {

    ApiScope value();

    /** 멱등성 키가 필수인 엔드포인트인지 (order:write, subscription:write). */
    boolean idempotent() default false;
}
