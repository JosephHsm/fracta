/**
 * 스캐폴딩 확인용 임시 화면. Phase 10 구현 순서 5에서 앱 관리 화면으로 교체된다.
 */
export default function Home() {
  return (
    <main className="mx-auto flex max-w-3xl flex-col gap-8 p-10">
      <header className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">FRACTA Developers</h1>
        <p className="text-fg-muted text-sm">개발자 포털 — 디자인 토큰 스모크 화면</p>
      </header>

      <section className="border-border bg-surface shadow-sm flex flex-col gap-3 rounded-lg border p-6">
        <h2 className="text-fg-muted text-xs font-medium tracking-wide uppercase">서피스 단계</h2>
        <div className="grid grid-cols-3 gap-3 text-sm">
          <div className="bg-surface-sunken border-border rounded-md border p-4">sunken</div>
          <div className="bg-surface border-border rounded-md border p-4">surface</div>
          <div className="bg-surface-elevated border-border shadow-md rounded-md border p-4">elevated</div>
        </div>
      </section>
    </main>
  );
}
