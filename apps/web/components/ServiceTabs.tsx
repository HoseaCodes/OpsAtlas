import type { Route } from "next";
import Link from "next/link";

const TABS = [
  ["overview", "Overview"],
  ["scorecard", "Scorecard"],
  ["manifest", "service.yaml"],
] as const;

/**
 * The detail tabs that are real.
 *
 * The prototype had seven: Overview, Blast radius, Observability, Cost,
 * Pipeline, Scorecard and service.yaml. Four of those need data this system
 * does not collect, so they are not rendered - not even disabled. CLAUDE.md §10:
 * no UI element implies a capability the backend does not have.
 *
 * Links rather than client state, so a tab is shareable and survives a reload.
 */
export function ServiceTabs({ slug, active }: { slug: string; active: string }) {
  return (
    <div className="flex gap-0.5 overflow-x-auto border-b border-rule px-4 pt-4 md:px-6" role="tablist">
      {TABS.map(([key, label]) => {
        const selected = key === active;
        return (
          <Link
            key={key}
            role="tab"
            aria-selected={selected}
            href={(key === "overview" ? `/catalog/${slug}` : `/catalog/${slug}?tab=${key}`) as Route}
            className={
              selected
                ? "-mb-px whitespace-nowrap border-b-2 px-2.5 pb-2.5 pt-2 font-medium text-ink"
                : "-mb-px whitespace-nowrap border-b-2 border-transparent px-2.5 pb-2.5 pt-2 text-ink-2 hover:text-ink"
            }
            style={selected ? { borderBottomColor: "var(--accent)" } : undefined}
          >
            {label}
          </Link>
        );
      })}
    </div>
  );
}
