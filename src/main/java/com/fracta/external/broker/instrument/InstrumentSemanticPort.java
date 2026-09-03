package com.fracta.external.broker.instrument;

import java.util.List;

/**
 * 종목명 의미 검색 포트.
 *
 * <p>문자 검색으로 안 잡히는 질의를 위한 것이다 — 마스터에는 "KODEX 200"인데 사람은
 * "코덱스"라고 친다. <b>LLM을 쓰지 않는다.</b> 로컬 임베딩으로 마스터 안에서만 고르므로
 * 존재하지 않는 종목코드가 나올 수 없고 호출 비용도 없다.
 */
public interface InstrumentSemanticPort {

    record Hit(String code, String name, String kind, double similarity) {
    }

    /** 실패하면 빈 목록을 준다 — 의미 검색은 부가 기능이고, 없으면 문자 검색만 남는다. */
    List<Hit> search(String query, int limit);
}
