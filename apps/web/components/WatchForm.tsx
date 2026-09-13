"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { Violation } from "@opsatlas/contracts";

/** Start watching a repository. */
export function WatchForm() {
  const router = useRouter();
  const [repository, setRepository] = useState("");
  const [ref, setRef] = useState("");
  const [path, setPath] = useState("service.yaml");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ detail: string; violations: Violation[] } | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);

    const response = await fetch("/api/sources", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ provider: "github", repository, ref: ref || undefined, path }),
    });

    setBusy(false);

    if (response.ok) {
      setRepository("");
      router.refresh();
      return;
    }

    const body = (await response.json()) as Record<string, unknown>;
    setError({
      detail: typeof body.detail === "string" ? body.detail : "The repository could not be watched.",
      violations: Array.isArray(body.violations) ? (body.violations as Violation[]) : [],
    });
  }

  return (
    <section className="border-b border-rule px-4 py-4 md:px-6">
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3">
        <div className="flex min-w-[240px] flex-1 flex-col gap-1.5">
          <label htmlFor="repository" className="text-[12.5px] text-ink-2">
            Repository
          </label>
          <input
            id="repository"
            value={repository}
            onChange={(event) => setRepository(event.target.value)}
            placeholder="owner/name"
            spellCheck={false}
            className="mono rounded-md border border-rule bg-surface px-2.5 py-1.5 text-[12.5px] text-ink placeholder:text-ink-3"
          />
        </div>

        <div className="flex w-[140px] flex-col gap-1.5">
          <label htmlFor="ref" className="text-[12.5px] text-ink-2">
            Branch
          </label>
          <input
            id="ref"
            value={ref}
            onChange={(event) => setRef(event.target.value)}
            placeholder="default"
            spellCheck={false}
            className="mono rounded-md border border-rule bg-surface px-2.5 py-1.5 text-[12.5px] text-ink placeholder:text-ink-3"
          />
        </div>

        <div className="flex w-[180px] flex-col gap-1.5">
          <label htmlFor="path" className="text-[12.5px] text-ink-2">
            Path
          </label>
          <input
            id="path"
            value={path}
            onChange={(event) => setPath(event.target.value)}
            spellCheck={false}
            className="mono rounded-md border border-rule bg-surface px-2.5 py-1.5 text-[12.5px] text-ink"
          />
        </div>

        <button
          type="submit"
          disabled={busy || repository.trim().length === 0}
          className="cursor-pointer rounded-md px-3 py-1.5 text-[14px] font-medium disabled:cursor-not-allowed disabled:opacity-50"
          style={{ background: "var(--accent)", color: "var(--bg)" }}
        >
          {busy ? "Watching…" : "Watch"}
        </button>
      </form>

      <p className="mt-2 text-[12px] text-ink-3">
        Public repositories need no credentials. Reading private ones needs a token with contents-read scope in{" "}
        <span className="mono">OPSATLAS_GITHUB_TOKEN</span> — never a token with write scope.
      </p>

      {error ? (
        <div role="alert" className="card mt-3 max-w-[80ch] border-l-[3px] p-3" style={{ borderLeftColor: "var(--alarm)" }}>
          <b className="block font-medium" style={{ color: "var(--alarm)" }}>
            {error.detail}
          </b>
          {error.violations.map((violation, index) => (
            <p key={index} className="mt-1.5 text-[12.5px] text-ink-2">
              <span className="mono">{violation.pointer}</span> — {violation.message}
            </p>
          ))}
        </div>
      ) : null}
    </section>
  );
}
