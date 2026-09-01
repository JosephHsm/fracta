import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** Tailwind 클래스 충돌을 뒤에 온 값 우선으로 합친다. */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
