import * as React from "react";

export function PageHeading({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
  return (
    <header className="mb-6 flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        <p className="text-fg-muted mt-1 text-sm">{description}</p>
      </div>
      {action}
    </header>
  );
}
