"use client";

import { FractaApiError } from "@fracta/api-client";
import { Button, Card, CardBody, Field, TextInput, errorMessage } from "@fracta/ui";
import { Building2 } from "lucide-react";
import { useRouter } from "next/navigation";
import * as React from "react";

import { useSession } from "@/lib/session";

/**
 * 로그인. 온보딩(가입 → KYC → 투자성향)은 /onboarding에서 이어진다.
 *
 * <p>실패 문구는 서버가 준 원시 코드가 아니라 매핑표를 거친다 —
 * 화면에 AUTH_INVALID_CREDENTIALS가 그대로 보이면 안 된다(phase-10 완료 조건).
 */
export default function LoginPage() {
  const router = useRouter();
  const { client, signIn, token, ready } = useSession();

  const [email, setEmail] = React.useState("demo@fracta.demo");
  const [password, setPassword] = React.useState("demo-password-1!");
  const [error, setError] = React.useState<string | null>(null);
  const [state, setState] = React.useState<"idle" | "pending">("idle");

  React.useEffect(() => {
    if (ready && token) router.replace("/");
  }, [ready, token, router]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setState("pending");
    try {
      const response = await client.auth.login({ loginRequest: { email, password } });
      const accessToken = response.data?.accessToken;
      if (!accessToken) throw new Error("토큰이 비어 있습니다");

      // 이름은 별도 조회 — JWT를 프론트에서 파싱하지 않는다
      signIn(accessToken, email.split("@")[0] ?? "투자자");
      router.replace("/");
    } catch (caught) {
      setError(
        caught instanceof FractaApiError
          ? errorMessage(caught.code)
          : "로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.",
      );
      setState("idle");
    }
  };

  return (
    <div className="flex min-h-dvh items-center justify-center p-6">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center gap-2 text-center">
          <Building2 aria-hidden className="text-accent size-8" />
          <h1 className="text-2xl font-semibold tracking-tight">FRACTA</h1>
          <p className="text-fg-muted text-sm">토큰증권 기반 조각투자 플랫폼</p>
        </div>

        <Card>
          <CardBody>
            <form onSubmit={submit} className="flex flex-col gap-4">
              <Field label="이메일" required>
                <TextInput
                  type="email"
                  autoComplete="username"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  required
                />
              </Field>

              <Field label="비밀번호" error={error ?? undefined} required>
                <TextInput
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  required
                />
              </Field>

              <Button type="submit" state={state} pendingLabel="로그인 중..." block>
                로그인
              </Button>
            </form>
          </CardBody>
        </Card>

        <p className="text-fg-subtle mt-4 text-center text-xs">
          데모 계정이 기본값으로 채워져 있습니다.
        </p>
      </div>
    </div>
  );
}
