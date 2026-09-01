"use client";

import { Field as BaseField } from "@base-ui-components/react/field";
import { AlertCircle } from "lucide-react";
import * as React from "react";

import { cn } from "../lib/cn";

/**
 * 폼 필드. Base UI Field가 label↔control↔error의 `id`/`aria-describedby` 연결을 맡는다.
 *
 * <p>오류는 색이 아니라 **문구**로 전달한다 (§11.4). 빨간 테두리만 있고 이유가 없으면
 * 색을 구분 못 하는 사용자에게는 아무 정보도 없다.
 */
export const inputClassName = cn(
  "bg-surface border-border text-fg placeholder:text-fg-subtle h-10 w-full rounded-md border px-3 text-sm",
  "transition-[border-color,box-shadow] duration-(--fr-motion-hover) ease-(--fr-ease-out)",
  "hover:border-border-strong",
  "data-[invalid]:border-danger data-[invalid]:bg-danger-soft/40",
  "disabled:cursor-not-allowed disabled:opacity-60",
);

export interface FieldProps {
  label: string;
  /** 입력을 돕는 설명. 오류 문구와 역할이 다르다 — 둘 다 필요할 수 있다. */
  description?: string;
  /** 서버가 준 오류 문구. 있으면 필드가 invalid 상태가 된다. */
  error?: string;
  required?: boolean;
  children: React.ReactNode;
  className?: string;
}

export function Field({ label, description, error, required, children, className }: FieldProps) {
  return (
    <BaseField.Root className={cn("flex flex-col gap-1.5", className)} invalid={Boolean(error)}>
      <BaseField.Label className="text-fg text-sm font-medium">
        {label}
        {required && (
          <span aria-hidden className="text-danger ml-0.5">
            *
          </span>
        )}
        {required && <span className="sr-only"> (필수)</span>}
      </BaseField.Label>

      {children}

      {description && !error && (
        <BaseField.Description className="text-fg-muted text-xs">{description}</BaseField.Description>
      )}

      {error && (
        <BaseField.Error
          match
          className="text-danger flex items-start gap-1.5 text-xs"
        >
          <AlertCircle aria-hidden className="mt-px size-3.5 shrink-0" />
          <span>{error}</span>
        </BaseField.Error>
      )}
    </BaseField.Root>
  );
}

export type TextInputProps = React.ComponentPropsWithoutRef<typeof BaseField.Control>;

export function TextInput({ className, ...props }: TextInputProps) {
  return <BaseField.Control className={cn(inputClassName, className)} {...props} />;
}
