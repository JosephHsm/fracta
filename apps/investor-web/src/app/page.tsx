import { PremiumBadge, PriceText } from "@fracta/ui";

/**
 * 스캐폴딩 확인용 임시 화면. Phase 10 구현 순서 4에서 홈(Bento)으로 교체된다.
 * 지금 확인하는 것은 두 가지다 — 디자인 토큰이 라이트/다크 양쪽에서 살아 있는지,
 * 괴리율 배지 3단계와 등락 색(상승 적색/하락 청색)이 규칙대로 나오는지.
 */
export default function Home() {
  return (
    <main className="mx-auto flex max-w-3xl flex-col gap-8 p-10">
      <header className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">FRACTA</h1>
        <p className="text-fg-muted text-sm">투자자 웹앱 — 디자인 토큰 스모크 화면</p>
      </header>

      <section className="border-border bg-surface shadow-sm flex flex-col gap-4 rounded-lg border p-6">
        <h2 className="text-fg-muted text-xs font-medium tracking-wide uppercase">괴리율 배지 (TR-08)</h2>
        <div className="flex flex-wrap gap-3">
          <PremiumBadge premiumRate={2.31} />
          <PremiumBadge premiumRate={-13.7} />
          <PremiumBadge premiumRate={24.5} />
          <PremiumBadge premiumRate={1.2} suspended />
        </div>
      </section>

      <section className="border-border bg-surface shadow-sm flex flex-col gap-4 rounded-lg border p-6">
        <h2 className="text-fg-muted text-xs font-medium tracking-wide uppercase">등락 색 (상승 적색 / 하락 청색)</h2>
        <div className="flex flex-wrap gap-6 text-lg">
          <PriceText change={1200}>+1,200 (2.31%)</PriceText>
          <PriceText change={-820}>-820 (1.58%)</PriceText>
          <PriceText change={0}>0 (0.00%)</PriceText>
        </div>
      </section>
    </main>
  );
}
