/**
 * 데모용 투자설명서 PDF 생성기.
 *
 * <p>`ai-service/tests/pdf_builder.py`를 JS로 옮긴 것이다. 라이브러리를 추가하지 않으려고
 * 직접 만든다(사양에 없는 의존성 금지). pdfplumber가 페이지별로 텍스트를 뽑을 수 있고
 * 브라우저 기본 뷰어가 `#page=N`으로 이동할 수 있으면 충분하다.
 *
 * <p><b>한계</b> — WinAnsi(ASCII) 범위만 쓴다. 한글을 넣으려면 CID 폰트 임베딩이 필요하고
 * 그건 별도 라이브러리 없이는 과하다. 실제 한글 설명서를 쓰려면
 * `POST /api/v1/issuances/{id}/prospectus`로 진짜 PDF를 올린 뒤 재인덱싱하면 된다.
 */

function escapeText(text) {
  return text.replace(/\\/g, "\\\\").replace(/\(/g, "\\(").replace(/\)/g, "\\)");
}

/** pages: string[][] — 페이지별 줄 목록. */
export function buildPdf(pages) {
  const objects = [];
  const add = (body) => {
    objects.push(Buffer.from(body, "latin1"));
    return objects.length; // 1-indexed 객체 번호
  };

  const fontId = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");

  const contentIds = [];
  for (const lines of pages) {
    // 줄마다 T*로 커서를 내린다 — 겹쳐 쓰면 텍스트 추출이 한 줄로 붙는다
    const parts = ["BT", "/F1 11 Tf", "60 740 Td", "16 TL"];
    for (const line of lines) {
      parts.push(`(${escapeText(line)}) Tj`, "T*");
    }
    parts.push("ET");
    const stream = parts.join("\n");
    contentIds.push(add(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`));
  }

  // Pages 객체 번호를 미리 계산해야 각 Page가 /Parent로 가리킬 수 있다
  const pagesObjId = objects.length + pages.length + 1;

  const pageIds = contentIds.map((contentId) =>
    add(
      `<< /Type /Page /Parent ${pagesObjId} 0 R /MediaBox [0 0 612 792] ` +
        `/Contents ${contentId} 0 R /Resources << /Font << /F1 ${fontId} 0 R >> >> >>`,
    ),
  );

  const kids = pageIds.map((id) => `${id} 0 R`).join(" ");
  const actualPagesId = add(`<< /Type /Pages /Kids [${kids}] /Count ${pageIds.length} >>`);
  if (actualPagesId !== pagesObjId) {
    throw new Error("Pages 객체 번호 예측이 어긋났다");
  }

  const catalogId = add(`<< /Type /Catalog /Pages ${pagesObjId} 0 R >>`);

  const chunks = [Buffer.from("%PDF-1.4\n", "latin1")];
  let length = chunks[0].length;
  const offsets = [];

  objects.forEach((body, index) => {
    offsets.push(length);
    const head = Buffer.from(`${index + 1} 0 obj\n`, "latin1");
    const tail = Buffer.from("\nendobj\n", "latin1");
    chunks.push(head, body, tail);
    length += head.length + body.length + tail.length;
  });

  const xrefAt = length;
  let xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) {
    xref += `${String(offset).padStart(10, "0")} 00000 n \n`;
  }
  xref +=
    `trailer\n<< /Size ${objects.length + 1} /Root ${catalogId} 0 R >>\n` +
    `startxref\n${xrefAt}\n%%EOF\n`;
  chunks.push(Buffer.from(xref, "latin1"));

  return Buffer.concat(chunks);
}

/**
 * 데모 투자설명서 본문. 페이지 경계가 뚜렷해야 AI 인용(`citedPages`)이 의미를 갖는다 —
 * 주제를 페이지마다 확실히 나눈다.
 */
export const DEMO_PROSPECTUS_PAGES = [
  [
    "FRACTA Securities Token Offering",
    "Prospectus - Page 1: Overview",
    "",
    "This offering divides ownership of a commercial real estate asset",
    "into fractional security tokens. Each token represents an equal",
    "undivided beneficial interest in the underlying property.",
    "",
    "Issuer: FRACTA Asset Management",
    "Asset type: Commercial real estate located in Seoul, Republic of Korea",
    "Total offering size: 100,000 fractional units",
    "Offering price per unit: KRW 10,000",
    "Minimum subscription: 1 unit",
  ],
  [
    "Prospectus - Page 2: Risk Factors",
    "",
    "Principal is not guaranteed. Investors may lose the entire amount",
    "invested. The following risks are material to this offering.",
    "",
    "Liquidity risk: the secondary market is thin. A small number of",
    "orders can move the traded price far from the appraised value of",
    "the underlying asset.",
    "",
    "Valuation divergence: when the traded price deviates from the",
    "reference price by more than 20 percent, trading in this token is",
    "suspended automatically until the divergence returns to normal.",
    "",
    "Vacancy risk: rental income depends on continued occupancy and may",
    "fall to zero during vacancy periods.",
  ],
  [
    "Prospectus - Page 3: Fees and Distributions",
    "",
    "Trading fee: 0.15 percent of the executed amount, charged to both",
    "the buyer and the seller at settlement.",
    "",
    "Subscription deposit: the full subscription amount is held from the",
    "investor cash balance at the time of application and refunded for",
    "any unallotted portion after allotment closes.",
    "",
    "Rental distributions are paid quarterly in proportion to the number",
    "of units held on the record date. Distribution amounts are not",
    "guaranteed and depend on net rental income after expenses.",
  ],
  [
    "Prospectus - Page 4: Allotment and Settlement",
    "",
    "Allotment method: first come, first served. If total subscriptions",
    "exceed the offering size, allotment follows the order in which",
    "applications were received.",
    "",
    "Settlement is delivery versus payment. Token delivery and cash",
    "payment occur atomically in a single ledger transaction; neither",
    "leg can complete without the other.",
    "",
    "All balances are recorded in an append only ledger. Ledger entries",
    "are never updated or deleted after they are written.",
  ],
  [
    "Prospectus - Page 5: Investor Suitability",
    "",
    "Under the Financial Consumer Protection Act, an investor whose",
    "assessed risk profile is more conservative than the risk grade of",
    "this product is blocked from subscribing.",
    "",
    "Such an investor may proceed only after signing an acknowledgement",
    "that the product has been determined unsuitable for their profile.",
    "",
    "The risk grade of this product is 3 (neutral).",
  ],
];
