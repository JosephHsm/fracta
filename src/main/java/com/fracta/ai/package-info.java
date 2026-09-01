/**
 * AI 모듈 — LlmPort 뒤에 격리된 RAG·가드레일 연동 (Phase 8).
 *
 * <p>RAG 파이프라인과 가드레일 자체는 별도 컨테이너(FastAPI, {@code ai-service/})에 있고,
 * 이 패키지는 HTTP 어댑터 + 서킷브레이커 + 이벤트 트리거만 담당한다.
 * 다른 모듈은 {@link com.fracta.ai.LlmPort} 로만 접근한다.
 */
package com.fracta.ai;
