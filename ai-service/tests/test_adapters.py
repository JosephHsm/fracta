"""어댑터 계약과 금지 파라미터 검증.

phase-08 §5 체크리스트의 '코드 검색' 항목들을 테스트로 고정한다. 사람이 눈으로
확인하고 넘어가면 다음 수정에서 조용히 다시 들어온다.
"""

import pathlib
import re

import pytest

from app.adapters.claude_adapter import parse_json_object
from app.adapters.embedding_adapters import FakeEmbeddingAdapter
from app.adapters.factory import build_embedding, build_llm
from app.adapters.fake_llm import FakeLlmAdapter
from app.config import Settings

APP_DIR = pathlib.Path(__file__).parent.parent / "app"
# LLM 요청을 조립하는 곳만 본다. app 전체를 훑으면 검색 top_k 같은 무관한 이름이
# 걸리고, 그걸 피하려다 오히려 진짜 사용처를 놓치게 된다.
ADAPTER_DIR = APP_DIR / "adapters"


def _source_files() -> list[pathlib.Path]:
    return sorted(APP_DIR.rglob("*.py"))


def _code_lines(path: pathlib.Path):
    """주석을 뺀 코드 줄만 (번호, 내용)으로 돌려준다."""
    for no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue
        yield no, line.split("  #", 1)[0]


# --- 금지 파라미터 (현행 모델에서 400) ---


@pytest.mark.parametrize("forbidden", ["budget_tokens", "temperature", "top_p", "top_k"])
def test_금지_파라미터를_사용하지_않는다(forbidden):
    """현행 모델에서 400이다. 문서에 이름을 언급하는 것과 실제로 넘기는 것을 구분한다."""
    usage = re.compile(rf"""["']?{forbidden}["']?\s*[=:]""")
    hits = [
        f"{path.relative_to(APP_DIR)}:{no}"
        for path in sorted(ADAPTER_DIR.rglob("*.py"))
        for no, line in _code_lines(path)
        if usage.search(line)
    ]
    assert not hits, f"{forbidden} 사용처: {hits}"


def test_어시스턴트_프리필을_쓰지_않는다():
    """프리필은 현행 모델에서 400이다. 형식 강제는 output_config.format 으로 한다."""
    hits = [
        f"{path.relative_to(APP_DIR)}:{no}"
        for path in _source_files()
        for no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1)
        if '"role": "assistant"' in line or "'role': 'assistant'" in line
    ]
    assert not hits, f"어시스턴트 턴 주입 의심: {hits}"


def test_모델_ID가_설정값으로만_등장한다():
    """하드코딩 0건 — config.py 의 기본값 한 곳에만 있어야 한다."""
    hits = [
        str(path.relative_to(APP_DIR))
        for path in _source_files()
        if "claude-opus-5" in path.read_text(encoding="utf-8")
    ]
    assert hits == ["config.py"], f"모델 ID 하드코딩: {hits}"


def test_모델_ID에_날짜_접미사가_없다():
    """존재하지 않는 ID다. 주석의 반례 표기는 제외하고 코드만 본다."""
    dated = re.compile(r"claude-[a-z0-9.\-]*-20\d{6}")
    hits = [
        f"{path.relative_to(APP_DIR)}:{no}"
        for path in _source_files()
        for no, line in _code_lines(path)
        if dated.search(line)
    ]
    assert not hits, f"날짜 접미사 모델 ID: {hits}"


def test_구조화_출력을_쓴다():
    source = (APP_DIR / "adapters" / "claude_adapter.py").read_text(encoding="utf-8")
    assert "output_config" in source
    assert '"json_schema"' in source
    # refusal 을 content 보다 먼저 본다
    assert source.index("stop_reason") < source.index("response.content")


def test_서버측_폴백_베타와_형식이_짝을_이룬다():
    """scalar 형식(fallbacks='default')은 -2026-07-01 헤더와만 맞다. 섞으면 400."""
    source = (APP_DIR / "adapters" / "claude_adapter.py").read_text(encoding="utf-8")
    assert 'fallbacks="default"' in source
    assert "server-side-fallback-2026-07-01" in source
    assert "server-side-fallback-2026-06-01" not in source


# --- JSON 파싱 ---


@pytest.mark.parametrize(
    "text,reason",
    [
        ("", "EMPTY_RESPONSE"),
        ("   ", "EMPTY_RESPONSE"),
        ("죄송합니다 답변할 수 없습니다", "JSON_DECODE"),
        ('{"answer": "잘린', "JSON_DECODE"),
        ("[1, 2, 3]", "NOT_AN_OBJECT"),
        ('"문자열"', "NOT_AN_OBJECT"),
    ],
)
def test_파싱_실패는_None을_돌려준다(text, reason):
    parsed, diagnostics = parse_json_object(text)
    assert parsed is None
    assert reason in diagnostics["parse_error"]


def test_정상_JSON은_파싱된다():
    parsed, diagnostics = parse_json_object('{"answer": "가", "cited_pages": [3]}')
    assert parsed == {"answer": "가", "cited_pages": [3]}
    assert diagnostics == {}


# --- 임베딩 대역 ---


def test_대역_임베딩은_결정적이다():
    adapter = FakeEmbeddingAdapter(dim=1024)
    a = adapter.embed(["투자설명서 위험요인"])[0]
    b = adapter.embed(["투자설명서 위험요인"])[0]
    assert a == b


def test_대역_임베딩은_L2_정규화된다():
    adapter = FakeEmbeddingAdapter(dim=1024)
    vector = adapter.embed(["공실률 상승 위험"])[0]
    norm = sum(x * x for x in vector) ** 0.5
    assert abs(norm - 1.0) < 1e-9


def test_대역_임베딩은_겹치는_문장에_높은_유사도를_준다():
    """난수 해시였다면 모든 유사도가 0이 되어 임계값 상한 경로를 못 만든다."""
    adapter = FakeEmbeddingAdapter(dim=1024)
    base, similar, unrelated = adapter.embed(
        [
            "주요 위험요인은 공실 발생 시 임대수익 감소다",
            "위험요인: 공실 발생 시 임대수익이 감소할 수 있다",
            "청약 단위는 1조각이며 조각당 공모가는 10,000원이다",
        ]
    )
    dot = lambda x, y: sum(a * b for a, b in zip(x, y))
    assert dot(base, similar) > dot(base, unrelated)


def test_빈_문자열도_0벡터를_만들지_않는다():
    """0벡터는 pgvector 코사인 연산에서 NaN 을 만든다."""
    vector = FakeEmbeddingAdapter(dim=8).embed([""])[0]
    assert any(x != 0 for x in vector)


# --- 팩토리 ---


def test_임베딩은_AI_PROVIDER를_따르지_않는다():
    """폐쇄망 전환은 LLM만 바꾼다. 임베딩까지 외부로 나가면 폐쇄망이 아니다."""
    for provider in ("claude", "ollama", "fake"):
        settings = Settings(ai_provider=provider, embedding_model="fake")
        embedder = build_embedding(settings)
        assert isinstance(embedder, FakeEmbeddingAdapter)


def test_알_수_없는_프로바이더는_거부된다():
    with pytest.raises(ValueError):
        build_llm(Settings(ai_provider="gpt"))


def test_fake_프로바이더로_LLM을_만든다():
    assert isinstance(build_llm(Settings(ai_provider="fake")), FakeLlmAdapter)


def test_임베딩_차원_기본값이_스키마와_일치한다():
    """V8__ai.sql 이 VECTOR(1024) 다. 어긋나면 INSERT 시점에 정체불명의 오류가 난다."""
    assert Settings().embedding_dim == 1024
    migration = (
        pathlib.Path(__file__).parents[2]
        / "src/main/resources/db/migration/V8__ai.sql"
    ).read_text(encoding="utf-8")
    assert "VECTOR(1024)" in migration
