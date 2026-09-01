"""PDF 페이지별 텍스트 추출 (pdfplumber)."""

import io

import pdfplumber

from app.indexing.chunker import PageText


def extract_pages(pdf_bytes: bytes) -> list[PageText]:
    """페이지별로 텍스트를 뽑는다. page_no 는 1-indexed."""
    pages: list[PageText] = []
    with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
        for zero_based, page in enumerate(pdf.pages):
            text = page.extract_text() or ""
            # 이미지 스캔 페이지 등 텍스트가 없는 페이지는 건너뛴다.
            # 건너뛰어도 page_no 는 실제 PDF 번호를 유지하므로 인용이 어긋나지 않는다.
            if text.strip():
                pages.append(PageText(page_no=zero_based + 1, text=text))
    return pages
