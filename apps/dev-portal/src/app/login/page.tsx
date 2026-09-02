"use client";

import { FractaApiError } from "@fracta/api-client";
import { Button, Card, CardBody, Field, TextInput, errorMessage } from "@fracta/ui";
import { Boxes } from "lucide-react";
import { useRouter } from "next/navigation";
import * as React from "react";

import { usePortalSession } from "@/lib/session";

export default function LoginPage() {
  const router = useRouter();
  const { client, signIn, ready, token } = usePortalSession();
  const [email, setEmail] = React.useState("demo@fracta.demo");
  const [password, setPassword] = React.useState("demo-password-1!");
  const [error, setError] = React.useState<string | null>(null);
  const [pending, setPending] = React.useState(false);

  React.useEffect(() => { if (ready && token) router.replace("/"); }, [ready, token, router]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setPending(true);
    try {
      const response = await client.auth.login({ loginRequest: { email, password } });
      const accessToken = response.data?.accessToken;
      if (!accessToken) throw new Error("로그인 토큰이 없습니다");
      signIn(accessToken, email.split("@")[0] ?? "개발자");
      router.replace("/");
    } catch (caught) {
      setError(caught instanceof FractaApiError ? errorMessage(caught.code) : "로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.");
      setPending(false);
    }
  };

  return (
    <div className="flex min-h-dvh items-center justify-center p-6">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center gap-2 text-center">
          <Boxes aria-hidden className="text-accent size-8" />
          <h1 className="text-2xl font-semibold tracking-tight">FRACTA Developers</h1>
          <p className="text-fg-muted text-sm">오픈 API를 만들고 관찰하는 개발자 포털</p>
        </div>
        <Card><CardBody><form onSubmit={submit} className="flex flex-col gap-4">
          <Field label="이메일" required><TextInput type="email" autoComplete="username" value={email} onChange={(event) => setEmail(event.target.value)} required /></Field>
          <Field label="비밀번호" error={error ?? undefined} required><TextInput type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required /></Field>
          <Button type="submit" block state={pending ? "pending" : "idle"}>로그인</Button>
        </form></CardBody></Card>
      </div>
    </div>
  );
}
