import type { ReactNode } from "react";

/**
 * The states a control plane has to render honestly.
 *
 * docs/design/tokens.md §7. Loading, empty and error are table stakes. Stale and
 * partial-failure are the ones that matter here: a control plane whose upstreams
 * are down is the normal case, not the exception, and a page that silently shows
 * less than it should is worse than one that says what it could not reach.
 */

export function Loading({ rows = 5, label }: { rows?: number; label: string }) {
  return (
    <div role="status" aria-live="polite" aria-busy="true">
      <span className="sr">{label}</span>
      {Array.from({ length: rows }, (_, index) => (
        // Skeletons match the real row height so nothing reflows on arrival.
        <div key={index} className="rule-b flex items-center gap-3 px-6 py-[13px]">
          <div className="skeleton h-4 w-48" />
          <div className="skeleton ml-auto h-3 w-24" />
          <div className="skeleton h-3 w-14" />
        </div>
      ))}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="max-w-[46ch] px-6 py-10 text-[13.5px] text-ink-2">
      <b className="mb-1.5 block font-medium text-ink">{title}</b>
      {children}
    </div>
  );
}

/**
 * Something failed, and the reader is told what to do about it.
 *
 * The correlation ID is shown because it is the only thing that turns "it
 * broke" into a log search someone can actually run.
 */
export function ErrorState({
  title,
  detail,
  correlationId,
}: {
  title: string;
  detail: string;
  correlationId?: string;
}) {
  return (
    <div
      role="alert"
      className="card m-6 max-w-[68ch] border-l-[3px] p-4"
      style={{ borderLeftColor: "var(--alarm)" }}
    >
      <b className="block font-medium" style={{ color: "var(--alarm)" }}>
        {title}
      </b>
      <p className="mt-1.5 text-[13px] text-ink-2">{detail}</p>
      {correlationId ? (
        <p className="mt-2 text-[12px] text-ink-3">
          Correlation ID <span className="mono">{correlationId}</span> — quote this when reporting it.
        </p>
      ) : null}
    </div>
  );
}

/**
 * Part of the page loaded and part did not.
 *
 * The rest of the page stays usable. Hiding the whole view because one panel
 * failed is the behaviour this state exists to prevent.
 */
export function PartialFailure({ what, detail, correlationId }: { what: string; detail: string; correlationId?: string }) {
  return (
    <div
      role="status"
      className="card border-l-[3px] p-3.5 text-[13px]"
      style={{ borderLeftColor: "var(--rule-2)" }}
    >
      <b className="font-medium text-ink">{what} could not be loaded</b>
      <p className="mt-1 text-ink-2">{detail}</p>
      <p className="mt-1 text-ink-2">Everything else on this page is current.</p>
      {correlationId ? <p className="mt-1.5 text-[12px] text-ink-3">Correlation ID <span className="mono">{correlationId}</span></p> : null}
    </div>
  );
}

/** Shown data is real but was read at a known earlier moment. */
export function Stale({ since }: { since: string }) {
  return (
    <p role="status" className="text-[12.5px] text-ink-3">
      Showing data last read {since}. The control plane did not respond to the most recent request.
    </p>
  );
}

/**
 * A capability the backend does not have yet.
 *
 * CLAUDE.md §10 forbids UI that implies a capability the backend lacks. Where a
 * column has to exist for the layout to make sense, this labels it instead of
 * filling it with something invented.
 */
export function NotYetMeasured({ children }: { children: ReactNode }) {
  return <span className="text-[12.5px] text-ink-3">{children}</span>;
}
