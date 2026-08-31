package com.fracta.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 스케줄링 활성화 — KYC Mock 지연 처리, IS-06 청약 개시 스캐너가 사용한다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
