export type Outcome =
  | "REGISTERED"
  | "UPDATED"
  | "UNCHANGED"
  | "REJECTED"
  | "CONFLICT"
  | "NOT_FOUND"
  | "UNAUTHORIZED"
  | "RATE_LIMITED"
  | "UNREACHABLE"
  | null;

const HEALTHY: ReadonlySet<string> = new Set(["REGISTERED", "UPDATED", "UNCHANGED"]);

const WORDS: Record<string, string> = {
  REGISTERED: "Registered",
  UPDATED: "Updated",
  UNCHANGED: "Unchanged",
  REJECTED: "Manifest rejected",
  CONFLICT: "Conflicts with another service",
  NOT_FOUND: "Manifest not found",
  UNAUTHORIZED: "Not authorised",
  RATE_LIMITED: "Rate limited",
  UNREACHABLE: "Could not reach GitHub",
};

/**
 * A sync outcome, as shape before colour.
 *
 * The same three-notch grammar the health meter uses: rising for a sync that
 * did its job, collapsed for one that did not, outlined for one that has never
 * run. A reader converting this page to greyscale still sees which sources are
 * working, which is the point of the whole encoding.
 */
export function SyncStatus({ outcome }: { outcome: Outcome }) {
  const state = outcome === null ? "never" : HEALTHY.has(outcome) ? "ok" : "failing";

  return (
    <span className="inline-flex items-center gap-2 text-[12.5px] text-ink-2">
      <span
        className="meter"
        data-state={state === "ok" ? "healthy" : state === "failing" ? "down" : "unobserved"}
        aria-hidden="true"
      >
        <i />
        <i />
        <i />
      </span>
      <span style={state === "failing" ? { color: "var(--alarm)" } : undefined}>
        {outcome === null ? "Never synced" : (WORDS[outcome] ?? outcome)}
      </span>
    </span>
  );
}

export function isHealthy(outcome: Outcome): boolean {
  return outcome !== null && HEALTHY.has(outcome);
}
