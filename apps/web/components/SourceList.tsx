"use client";

import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import type { components } from "@opsatlas/contracts";
import { SyncStatus, type Outcome } from "./SyncStatus";
import { relativeTime, absoluteTime } from "@/lib/format";

type SourceView = components["schemas"]["SourceView"];

/**
 * Watched repositories and how their last sync went.
 *
 * Two timestamps are shown rather than one, because together they answer the
 * question a reader of a failing source actually has: "how current is what I am
 * looking at". Last attempted alone does not, and last succeeded alone does not.
 */
export function SourceList({ sources }: { sources: SourceView[] }) {
  return (
    <ul className="m-0 list-none p-0">
      {sources.map((source) => (
        <SourceRow key={source.id} source={source} />
      ))}
    </ul>
  );
}

function SourceRow({ source }: { source: SourceView }) {
  const router = useRouter();
  const [busy, setBusy] = useState<string | null>(null);

  async function act(action: "sync" | "enable" | "disable") {
    setBusy(action);
    await fetch(`/api/sources?action=${action}&id=${encodeURIComponent(source.id)}`, { method: "POST" });
    setBusy(null);
    router.refresh();
  }

  async function unwatch() {
    setBusy("unwatch");
    await fetch(`/api/sources?id=${encodeURIComponent(source.id)}`, { method: "DELETE" });
    setBusy(null);
    router.refresh();
  }

  return (
    <li className="border-b border-rule px-4 py-3.5 md:px-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <b className="mono block text-[13px] font-medium">
            {source.repository}
            <span className="text-ink-3">
              /{source.path}
              {source.ref === "HEAD" ? "" : ` @ ${source.ref}`}
            </span>
          </b>
          <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1">
            <SyncStatus outcome={source.lastOutcome as Outcome} />
            {!source.enabled ? <span className="text-[12.5px] text-ink-3">polling paused</span> : null}
            {source.serviceId ? (
              <Link
                href={"/catalog" as Route}
                className="text-[12.5px] underline"
                style={{ color: "var(--accent)" }}
              >
                registered in the catalog
              </Link>
            ) : null}
          </div>
        </div>

        <div className="flex flex-none flex-wrap gap-1.5">
          <Action label="Sync now" busy={busy === "sync"} onClick={() => act("sync")} />
          <Action
            label={source.enabled ? "Pause" : "Resume"}
            busy={busy === "enable" || busy === "disable"}
            onClick={() => act(source.enabled ? "disable" : "enable")}
          />
          <Action label="Unwatch" busy={busy === "unwatch"} onClick={unwatch} />
        </div>
      </div>

      <dl className="mt-2.5 flex flex-wrap gap-x-6 gap-y-1 text-[12px] text-ink-3">
        <div className="flex gap-1.5">
          <dt>Last attempted</dt>
          <dd className="m-0" title={source.lastAttemptAt ? absoluteTime(source.lastAttemptAt) : undefined}>
            {source.lastAttemptAt ? relativeTime(source.lastAttemptAt) : "never"}
          </dd>
        </div>
        <div className="flex gap-1.5">
          <dt>Last succeeded</dt>
          <dd className="m-0" title={source.lastSuccessAt ? absoluteTime(source.lastSuccessAt) : undefined}>
            {source.lastSuccessAt ? relativeTime(source.lastSuccessAt) : "never"}
          </dd>
        </div>
        {source.consecutiveFailures > 0 ? (
          <div className="flex gap-1.5">
            <dt>Consecutive failures</dt>
            <dd className="mono m-0">{source.consecutiveFailures}</dd>
          </div>
        ) : null}
      </dl>

      {source.lastDetail ? (
        // The control plane's own explanation, shown verbatim. The owning team
        // can see what broke without leaving the catalog.
        <p
          className="mt-2.5 max-w-[90ch] border-l-[3px] py-0.5 pl-3 text-[12.5px] text-ink-2"
          style={{ borderLeftColor: "var(--alarm)" }}
        >
          {source.lastDetail}
        </p>
      ) : null}
    </li>
  );
}

function Action({ label, busy, onClick }: { label: string; busy: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      className="cursor-pointer rounded-[5px] border border-rule-2 bg-transparent px-2 py-1 text-[12px] text-ink-2 hover:border-ink-3 hover:text-ink disabled:cursor-not-allowed disabled:opacity-50"
    >
      {busy ? "…" : label}
    </button>
  );
}
