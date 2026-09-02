"use client";

import { Button, Card, CardBody } from "@fracta/ui";
import { AlertTriangle, Check, Copy } from "lucide-react";
import * as React from "react";

export function SecretReveal({ label, value, onDone }: { label: string; value: string; onDone: () => void }) {
  const [copied, setCopied] = React.useState(false);
  const copy = async () => { await navigator.clipboard.writeText(value); setCopied(true); };
  return (
    <Card elevated className="border-warning/40"><CardBody className="flex flex-col gap-4">
      <div className="flex items-start gap-3"><AlertTriangle aria-hidden className="text-warning mt-0.5 size-5 shrink-0" /><div><h2 className="font-semibold">{label}을 지금 복사해 주세요</h2><p className="text-fg-muted mt-1 text-sm">이 값은 다시 볼 수 없습니다. 안전한 시크릿 저장소에 보관해 주세요.</p></div></div>
      <div className="bg-surface-sunken border-border flex items-center gap-2 rounded-md border p-3"><code className="min-w-0 flex-1 overflow-x-auto text-sm">{value}</code><Button size="sm" variant="secondary" onClick={copy}>{copied ? <Check aria-hidden className="size-4" /> : <Copy aria-hidden className="size-4" />}{copied ? "복사됨" : "복사"}</Button></div>
      <Button variant="secondary" onClick={onDone}>보관을 완료했습니다</Button>
    </CardBody></Card>
  );
}
