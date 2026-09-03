"""종목명 의미 검색.

DB 없이 검증한다 — 연결을 대역으로 바꿔 넣는다. 여기서 확인할 것은 SQL이 아니라
**정책**이다. 임계값 미만을 버리는가, 빈 질의를 거르는가, 배치로 임베딩하는가.
"""
from __future__ import annotations

from contextlib import contextmanager

import pytest

from app.adapters.embedding_adapters import FakeEmbeddingAdapter
from app.instruments import InstrumentSemanticIndex


class FakeCursor:
    def __init__(self, rows):
        self._rows = rows

    def fetchall(self):
        return self._rows


class FakeConnection:
    """execute를 기록하고 미리 정한 결과를 돌려준다."""

    def __init__(self, results):
        self._results = list(results)
        self.executed = []
        self.committed = False

    def execute(self, sql, params=None):
        self.executed.append((sql, params))
        return FakeCursor(self._results.pop(0) if self._results else [])

    def commit(self):
        self.committed = True


def connector(conn):
    @contextmanager
    def _connect():
        yield conn

    return _connect


def test_search_drops_results_below_threshold():
    # 벡터 검색은 항상 가장 가까운 것을 돌려준다 — "asdf"를 쳐도 뭔가 나온다.
    # 문자 검색이 0건일 때의 폴백이므로, 관련 없는 결과를 내느니 빈 목록이 낫다.
    conn = FakeConnection([[
        ("069500", "KODEX 200", "ETF", 0.81),
        ("133690", "TIGER 미국나스닥100", "ETF", 0.20),
    ]])
    index = InstrumentSemanticIndex(connector(conn), FakeEmbeddingAdapter(dim=1024))

    hits = index.search("코덱스", limit=10, min_similarity=0.35)

    assert [h.code for h in hits] == ["069500"]
    assert hits[0].name == "KODEX 200"


def test_blank_query_does_not_touch_database():
    conn = FakeConnection([])
    index = InstrumentSemanticIndex(connector(conn), FakeEmbeddingAdapter(dim=1024))

    assert index.search("   ") == []
    assert conn.executed == []


def test_reindex_replaces_previous_vectors():
    # 상장폐지 종목이 남으면 검색에 유령이 뜬다. 전량 교체가 맞다.
    conn = FakeConnection([[("069500", "KODEX 200"), ("365550", "ESR켄달스퀘어리츠")]])
    index = InstrumentSemanticIndex(connector(conn), FakeEmbeddingAdapter(dim=1024))

    assert index.reindex() == 2
    assert any("DELETE FROM instrument_embedding" in sql for sql, _ in conn.executed)
    assert conn.committed


def test_reindex_targets_only_fractionable_kinds():
    # 일반 주식 4천 종목까지 넣으면 인덱싱이 길어지고 검색 결과도 흐려진다.
    conn = FakeConnection([[]])
    index = InstrumentSemanticIndex(connector(conn), FakeEmbeddingAdapter(dim=1024))

    index.reindex()

    _, params = conn.executed[0]
    assert params == (["ETF", "REIT"],)
