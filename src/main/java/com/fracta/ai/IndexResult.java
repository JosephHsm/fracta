package com.fracta.ai;

/** 인덱싱 결과. chunks가 0이면 텍스트를 못 뽑은 것이다(스캔 PDF 등). */
public record IndexResult(long issuanceId, int pages, int chunks, String embeddingModel) {
}
