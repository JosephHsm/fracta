import { Button, Card, CardBody } from "@fracta/ui";
import { AppWindow } from "lucide-react";
import Link from "next/link";

export function NoClient() {
  return (
    <Card>
      <CardBody className="flex flex-col items-center gap-3 py-12 text-center">
        <AppWindow aria-hidden className="text-fg-subtle size-8" />
        <div><h2 className="font-semibold">등록된 앱이 없습니다</h2><p className="text-fg-muted mt-1 text-sm">샌드박스 앱을 먼저 등록해 주세요.</p></div>
        <Link href="/apps"><Button>앱 등록하기</Button></Link>
      </CardBody>
    </Card>
  );
}
