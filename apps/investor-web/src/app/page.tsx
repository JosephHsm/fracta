"use client";

import {
  Badge,
  BentoGrid,
  BentoItem,
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  CommandHint,
  CommandPalette,
  Dialog,
  Field,
  Money,
  PremiumBadge,
  PriceText,
  ProgressBar,
  StatTile,
  Table,
  TableContainer,
  Tbody,
  Td,
  TextInput,
  Th,
  Thead,
  ThemeToggle,
  Tooltip,
  TooltipProvider,
  Tr,
  Units,
  errorMessage,
  formatDDay,
  useToast,
} from "@fracta/ui";
import { Coins, LayoutDashboard, Wallet } from "lucide-react";
import * as React from "react";

/** 미리보기용 고정 마감일. 실제 화면에서는 서버가 준 청약 종료 시각을 쓴다. */
const SAMPLE_END_AT = new Date("2026-09-04T09:00:00+09:00");

/**
 * 디자인 시스템 미리보기. Phase 10 구현 순서 4에서 홈(Bento)으로 교체된다.
 * 지금은 공용 컴포넌트가 라이트/다크 양쪽에서 규칙대로 렌더되는지 확인하는 용도다.
 */
export default function Home() {
  return (
    <TooltipProvider>
      <Preview />
    </TooltipProvider>
  );
}

function Preview() {
  const toast = useToast();
  const [dialogOpen, setDialogOpen] = React.useState(false);
  const [state, setState] = React.useState<"idle" | "pending" | "done">("idle");

  const apply = () => {
    setState("pending");
    window.setTimeout(() => setState("done"), 900);
  };

  return (
    <div className="min-h-dvh">
      <header className="fr-glass border-border sticky top-0 z-30 border-b">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-6 py-3">
          <span className="font-semibold tracking-tight">FRACTA</span>
          <div className="flex items-center gap-3">
            <CommandHint />
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="mx-auto flex max-w-6xl flex-col gap-6 p-6">
        <BentoGrid>
          <BentoItem span={4}>
            <StatTile
              label="총 평가금액"
              value={<Money amount={24_520_000} size="xl" />}
              hint={<PriceText change={318_000}>+318,000 (1.31%)</PriceText>}
              icon={<Wallet aria-hidden className="size-3.5" />}
            />
          </BentoItem>
          <BentoItem span={4}>
            <StatTile
              label="청약 가능 금액"
              value={<Money amount={7_800_000} size="xl" />}
              hint="예수금 기준"
              icon={<Coins aria-hidden className="size-3.5" />}
            />
          </BentoItem>
          <BentoItem span={4}>
            <StatTile
              label="보유 종목"
              value={<Units units={1_240} />}
              hint="3개 종목"
              icon={<LayoutDashboard aria-hidden className="size-3.5" />}
            />
          </BentoItem>

          <BentoItem span={8}>
            <Card>
              <CardHeader>
                <CardTitle>괴리율 배지 (TR-08)</CardTitle>
              </CardHeader>
              <CardBody className="flex flex-wrap gap-3">
                <PremiumBadge premiumRate={2.31} />
                <PremiumBadge premiumRate={-13.7} />
                <PremiumBadge premiumRate={24.5} />
                <PremiumBadge premiumRate={1.2} suspended />
                <PremiumBadge premiumRate={null} />
              </CardBody>
            </Card>
          </BentoItem>

          <BentoItem span={4}>
            <Card className="h-full">
              <CardHeader>
                <CardTitle>모집 진행률</CardTitle>
              </CardHeader>
              <CardBody className="flex flex-col gap-4">
                <ProgressBar value={72} label="한남동 상업시설" />
                <div className="flex items-center gap-2">
                  <Badge tone="warning">{formatDDay(SAMPLE_END_AT)}</Badge>
                  <Tooltip content="청약 마감까지 남은 일수다">
                    <span className="text-fg-muted cursor-help text-xs underline decoration-dotted">
                      D-Day란?
                    </span>
                  </Tooltip>
                </div>
              </CardBody>
            </Card>
          </BentoItem>

          <BentoItem span={12}>
            <TableContainer>
              <Table>
                <Thead>
                  <Tr>
                    <Th>종목</Th>
                    <Th>체결가</Th>
                    <Th>수량</Th>
                    <Th>등락</Th>
                  </Tr>
                </Thead>
                <Tbody>
                  <Tr entering>
                    <Td>한남동 상업시설</Td>
                    <Td>
                      <Money amount={10_200} />
                    </Td>
                    <Td>
                      <Units units={30} />
                    </Td>
                    <Td>
                      <PriceText change={200}>+200 (2.00%)</PriceText>
                    </Td>
                  </Tr>
                  <Tr>
                    <Td>성수동 지식산업센터</Td>
                    <Td>
                      <Money amount={9_850} />
                    </Td>
                    <Td>
                      <Units units={12} />
                    </Td>
                    <Td>
                      <PriceText change={-150}>-150 (1.50%)</PriceText>
                    </Td>
                  </Tr>
                </Tbody>
              </Table>
            </TableContainer>
          </BentoItem>

          <BentoItem span={6}>
            <Card className="h-full">
              <CardHeader>
                <CardTitle>버튼 상태 전이</CardTitle>
              </CardHeader>
              <CardBody className="flex flex-col gap-3">
                <Button state={state} onClick={apply} pendingLabel="처리 중..." doneLabel="청약 완료">
                  청약하기
                </Button>
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" size="sm" onClick={() => setState("idle")}>
                    되돌리기
                  </Button>
                  <Button variant="accent" size="sm" onClick={() => setDialogOpen(true)}>
                    모달 열기
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      toast.show({
                        title: "주문이 접수됐다",
                        description: "체결되면 알림으로 알려준다.",
                        tone: "success",
                      })
                    }
                  >
                    토스트
                  </Button>
                  <Button
                    variant="danger"
                    size="sm"
                    onClick={() =>
                      toast.show({
                        title: "주문 실패",
                        description: errorMessage("FUND_INSUFFICIENT_CASH"),
                        tone: "danger",
                      })
                    }
                  >
                    에러 문구
                  </Button>
                </div>
              </CardBody>
            </Card>
          </BentoItem>

          <BentoItem span={6}>
            <Card className="h-full">
              <CardHeader>
                <CardTitle>폼 필드</CardTitle>
              </CardHeader>
              <CardBody className="flex flex-col gap-4">
                <Field label="청약 수량" description="1조각 단위로 신청한다" required>
                  <TextInput type="number" defaultValue={10} min={1} />
                </Field>
                <Field label="이메일" error={errorMessage("VALID_DUPLICATE_EMAIL")}>
                  <TextInput type="email" defaultValue="test@fracta.dev" />
                </Field>
              </CardBody>
            </Card>
          </BentoItem>
        </BentoGrid>
      </main>

      <Dialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        title="청약 신청 확인"
        description="확인 후에는 취소가 제한된다."
        dismissible={false}
        footer={
          <>
            <Button variant="secondary" onClick={() => setDialogOpen(false)}>
              취소
            </Button>
            <Button onClick={() => setDialogOpen(false)}>확인</Button>
          </>
        }
      >
        <dl className="flex flex-col gap-2">
          <div className="flex justify-between">
            <dt className="text-fg-muted">신청 수량</dt>
            <dd>
              <Units units={10} />
            </dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-fg-muted">증거금</dt>
            <dd>
              <Money amount={100_000} />
            </dd>
          </div>
        </dl>
      </Dialog>

      <CommandPalette
        items={[
          {
            id: "home",
            label: "홈",
            group: "이동",
            hint: "대시보드",
            onSelect: () => undefined,
          },
          {
            id: "hannam",
            label: "한남동 상업시설",
            keywords: ["FR-T-001", "hannam"],
            group: "종목",
            onSelect: () => undefined,
          },
        ]}
      />
    </div>
  );
}
