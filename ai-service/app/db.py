"""DB 접근. 스키마 소유는 Core(Flyway)이고 여기서는 읽기·쓰기만 한다.

네이티브 SQL만 쓰되 바인딩 파라미터만 사용한다 — 문자열 연결 금지.
"""

from contextlib import contextmanager
from typing import Any, Iterator

import psycopg
from pgvector.psycopg import register_vector
from psycopg_pool import ConnectionPool

_pool: ConnectionPool | None = None


def init_pool(dsn: str, min_size: int = 1, max_size: int = 8) -> None:
    global _pool
    if _pool is not None:
        return

    def _configure(conn: psycopg.Connection) -> None:
        # VECTOR 타입 어댑터 등록. 없으면 리스트를 넘길 때 타입 오류가 난다
        register_vector(conn)

    _pool = ConnectionPool(
        dsn, min_size=min_size, max_size=max_size, configure=_configure, open=True
    )


def close_pool() -> None:
    global _pool
    if _pool is not None:
        _pool.close()
        _pool = None


@contextmanager
def connection() -> Iterator[psycopg.Connection]:
    if _pool is None:
        raise RuntimeError("DB 풀이 초기화되지 않았다. init_pool() 을 먼저 호출한다")
    with _pool.connection() as conn:
        yield conn


def healthy() -> dict[str, Any]:
    try:
        with connection() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
                cur.fetchone()
        return {"reachable": True}
    except Exception as exc:  # noqa: BLE001 - 헬스체크는 어떤 실패도 상태로 환원한다
        return {"reachable": False, "error": type(exc).__name__}
