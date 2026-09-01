"""테스트용 최소 PDF 생성기.

reportlab 같은 라이브러리를 추가하지 않기 위해 직접 만든다(사양에 없는 의존성 금지).
pdfplumber/pdfminer 가 실제로 열고 페이지별로 텍스트를 뽑을 수 있는 수준이면 충분하므로
xref 오프셋까지만 정확히 맞추고 나머지는 최소한으로 둔다.

WinAnsi 범위(ASCII)만 쓴다 — 한글을 넣으려면 CID 폰트 임베딩이 필요하고, 그건
이 테스트가 확인하려는 것(페이지 경계 유지)과 무관한 복잡도다.
"""

from __future__ import annotations


def _escape(text: str) -> str:
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def build_pdf(pages: list[list[str]]) -> bytes:
    """페이지별 텍스트 줄 목록을 받아 PDF 바이트를 만든다.

    pages = [["page one line a", "line b"], ["page two line a"]]
    """
    objects: list[bytes] = []

    def add(body: str) -> int:
        objects.append(body.encode("latin-1"))
        return len(objects)  # 1-indexed 객체 번호

    font_id = 1
    objects.append(
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    )

    page_ids: list[int] = []
    content_ids: list[int] = []
    for lines in pages:
        # 줄마다 Td 로 커서를 내린다. 좌표는 텍스트 추출에만 영향 없지만
        # 겹쳐 쓰면 pdfminer 가 한 줄로 붙여버려 검증이 흐려진다.
        parts = ["BT", "/F1 12 Tf", "72 720 Td", "14 TL"]
        for line in lines:
            parts.append(f"({_escape(line)}) Tj")
            parts.append("T*")
        parts.append("ET")
        stream = "\n".join(parts)
        content_ids.append(add(f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream"))
        page_ids.append(0)  # 자리만 잡아두고 pages 객체 번호를 안 뒤에 채운다

    pages_obj_id = len(objects) + len(pages) + 1

    for index, content_id in enumerate(content_ids):
        page_ids[index] = add(
            f"<< /Type /Page /Parent {pages_obj_id} 0 R "
            f"/MediaBox [0 0 612 792] /Contents {content_id} 0 R "
            f"/Resources << /Font << /F1 {font_id} 0 R >> >> >>"
        )

    kids = " ".join(f"{pid} 0 R" for pid in page_ids)
    actual_pages_id = add(f"<< /Type /Pages /Kids [{kids}] /Count {len(page_ids)} >>")
    assert actual_pages_id == pages_obj_id, "Pages 객체 번호 예측이 어긋났다"

    catalog_id = add(f"<< /Type /Catalog /Pages {pages_obj_id} 0 R >>")

    out = bytearray(b"%PDF-1.4\n")
    offsets: list[int] = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode("latin-1") + body + b"\nendobj\n"

    xref_at = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode("latin-1")
    out += b"0000000000 65535 f \n"
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode("latin-1")
    out += (
        f"trailer\n<< /Size {len(objects) + 1} /Root {catalog_id} 0 R >>\n"
        f"startxref\n{xref_at}\n%%EOF\n"
    ).encode("latin-1")

    return bytes(out)
