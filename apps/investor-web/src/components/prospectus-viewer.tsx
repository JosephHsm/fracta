"use client";

import { FractaApiError } from "@fracta/api-client";
import { Card, CardBody, Skeleton, errorMessage } from "@fracta/ui";
import { FileText } from "lucide-react";
import * as React from "react";

import { useSession } from "@/lib/session";

/**
 * 투자설명서 PDF 뷰어 (FSD §11.2).
 *
 * <p>브라우저 기본 PDF 렌더러를 쓴다. PDF.js 같은 라이브러리를 얹지 않는 이유는
 * 사양(§11.4 요소 기술)에 없기도 하고, `#page=N` 이동이라는 요구사항을 기본 뷰어가
 * 이미 지원하기 때문이다.
 *
 * <p><b>blob URL을 쓰는 이유</b> — PDF 엔드포인트는 인증이 필요한데 `<iframe src>`는
 * Authorization 헤더를 실을 수 없다. 토큰을 붙여 받아온 바이트를 blob으로 만들어 띄운다.
 * URL은 언마운트 때 반드시 해제한다(안 하면 페이지를 옮길 때마다 메모리가 샌다).
 */
export function ProspectusViewer({ issuanceId, page }: { issuanceId: number; page: number }) {
  const { client } = useSession();
  const [blobUrl, setBlobUrl] = React.useState<string | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    let revoked = false;
    let url: string | null = null;

    void (async () => {
      try {
        const response = await client.issuance.downloadProspectusRaw({ id: issuanceId });
        const blob = await response.raw.blob();
        if (revoked) return;
        url = URL.createObjectURL(blob);
        setBlobUrl(url);
      } catch (caught) {
        if (revoked) return;
        setError(
          caught instanceof FractaApiError && caught.status === 404
            ? "아직 투자설명서가 등록되지 않았습니다."
            : caught instanceof FractaApiError
              ? errorMessage(caught.code)
              : "투자설명서를 불러오지 못했습니다.",
        );
      }
    })();

    return () => {
      revoked = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [client, issuanceId]);

  if (error) {
    return (
      <Card className="h-full">
        <CardBody className="text-fg-muted flex h-full flex-col items-center justify-center gap-3 py-20 text-sm">
          <FileText aria-hidden className="text-fg-subtle size-8" />
          <p>{error}</p>
        </CardBody>
      </Card>
    );
  }

  if (!blobUrl) {
    return <Skeleton className="h-[70vh] w-full rounded-lg" />;
  }

  return (
    <Card className="h-full overflow-hidden">
      {/*
        key에 page를 넣어 iframe을 다시 만든다. 같은 문서 안에서 프래그먼트만 바꾸면
        브라우저가 이동하지 않는 경우가 있어, 인용 클릭이 안 먹는 것처럼 보인다.
      */}
      <iframe
        key={page}
        title="투자설명서"
        src={`${blobUrl}#page=${page}`}
        className="h-[70vh] w-full border-0"
      />
    </Card>
  );
}
