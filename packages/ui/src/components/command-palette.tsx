"use client";

import { Command } from "cmdk";
import * as React from "react";

import { cn } from "../lib/cn";

/**
 * 커맨드 팔레트 (`Ctrl+K` / `⌘K`). Keyboard-first UX — 마우스 없이 종목 검색과
 * 주요 화면 이동이 끝나야 의미가 있다 (§11.4).
 */
export interface CommandItem {
  id: string;
  label: string;
  /** 검색어 매칭을 넓히는 보조 키워드 (종목 코드, 영문명 등) */
  keywords?: string[];
  group: string;
  icon?: React.ReactNode;
  hint?: string;
  onSelect: () => void;
}

export interface CommandPaletteProps {
  items: CommandItem[];
  placeholder?: string;
  emptyLabel?: string;
}

export function CommandPalette({
  items,
  placeholder = "명령 또는 종목 검색...",
  emptyLabel = "결과가 없다",
}: CommandPaletteProps) {
  const [open, setOpen] = React.useState(false);

  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key.toLowerCase() === "k" && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        setOpen((previous) => !previous);
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, []);

  const groups = React.useMemo(() => {
    const map = new Map<string, CommandItem[]>();
    for (const item of items) {
      const bucket = map.get(item.group);
      if (bucket) bucket.push(item);
      else map.set(item.group, [item]);
    }
    return [...map.entries()];
  }, [items]);

  return (
    <Command.Dialog
      open={open}
      onOpenChange={setOpen}
      label="명령 팔레트"
      className={cn(
        "fixed top-[18vh] left-1/2 z-50 -translate-x-1/2",
        "w-[min(36rem,calc(100vw-2rem))]",
        "fr-glass border-border shadow-lg overflow-hidden rounded-xl border",
      )}
      overlayClassName="fixed inset-0 z-40 bg-black/40"
    >
      <Command.Input
        placeholder={placeholder}
        className="border-border text-fg placeholder:text-fg-subtle h-12 w-full border-b bg-transparent px-4 text-sm outline-none"
      />
      <Command.List className="max-h-80 overflow-y-auto p-2">
        <Command.Empty className="text-fg-muted px-3 py-8 text-center text-sm">
          {emptyLabel}
        </Command.Empty>

        {groups.map(([group, groupItems]) => (
          <Command.Group
            key={group}
            heading={group}
            className="[&_[cmdk-group-heading]]:text-fg-subtle [&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:font-medium"
          >
            {groupItems.map((item) => (
              <Command.Item
                key={item.id}
                value={[item.label, ...(item.keywords ?? [])].join(" ")}
                onSelect={() => {
                  setOpen(false);
                  item.onSelect();
                }}
                className={cn(
                  "flex cursor-pointer items-center gap-2.5 rounded-md px-3 py-2 text-sm",
                  "data-[selected=true]:bg-accent-soft data-[selected=true]:text-accent",
                )}
              >
                {item.icon}
                <span className="flex-1">{item.label}</span>
                {item.hint && <span className="text-fg-subtle text-xs">{item.hint}</span>}
              </Command.Item>
            ))}
          </Command.Group>
        ))}
      </Command.List>
    </Command.Dialog>
  );
}

/** 헤더에 놓는 힌트 버튼. 팔레트가 있다는 걸 모르면 아무도 쓰지 않는다. */
export function CommandHint({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        "text-fg-subtle border-border bg-surface-sunken hidden items-center gap-1 rounded-md border px-2 py-1 text-xs sm:inline-flex",
        className,
      )}
    >
      <kbd className="font-sans">Ctrl</kbd>
      <span aria-hidden>+</span>
      <kbd className="font-sans">K</kbd>
    </span>
  );
}
