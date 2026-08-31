package com.fracta.support;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.common.money.InsufficientUnitsException;
import com.fracta.common.response.ApiResponse;

/** 테스트 전용 컨트롤러 — 공통 응답·예외 포맷 검증용. 테스트 클래스패스에만 존재한다. */
@RestController
public class PingController {

    @GetMapping("/api/v1/test/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.of(Map.of("pong", true));
    }

    @GetMapping("/api/v1/test/boom")
    public ApiResponse<Void> boom() {
        throw new InsufficientUnitsException(1, 5);
    }
}
