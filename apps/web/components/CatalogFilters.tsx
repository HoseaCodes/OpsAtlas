"use client";

import type { Route } from "next";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";

/**
 * Search and tier filter.
 *
 * State lives in the URL so a filtered view is shareable and survives a reload.
 * The filtering itself happens on the server page, over the current page of
 * results only - the control plane has no search endpoint yet, and the empty
 * state says so rather than letting a reader assume the fleet was searched.
 */
export function CatalogFilters() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const [query, setQuery] = useState(searchParams.get("q") ?? "");
  const tier = searchParams.get("tier") ?? "";

  // Debounced so typing does not push a history entry per keystroke.
  useEffect(() => {
    const timer = setTimeout(() => {
      const next = new URLSearchParams(searchParams.toString());
      if (query) next.set("q", query);
      else next.delete("q");
      next.delete("cursor");
      // typedRoutes cannot verify a query string built at runtime; the path
      // itself is still a checked route.
      router.replace(`${pathname}?${next}` as Route, { scroll: false });
    }, 200);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

  function tierHref(value: string): Route {
    const next = new URLSearchParams(searchParams.toString());
    if (value) next.set("tier", value);
    else next.delete("tier");
    next.delete("cursor");
    return `${pathname}?${next}` as Route;
  }

  return (
    <div className="flex flex-wrap items-center gap-2.5 py-4">
      <div className="relative flex min-w-[200px] flex-1 items-center">
        <label className="sr" htmlFor="catalog-search">
          Search services on this page
        </label>
        <input
          id="catalog-search"
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search by name, repository or runtime"
          className="w-full rounded-md border border-rule bg-surface px-2.5 py-1.5 text-[14px] text-ink placeholder:text-ink-3"
        />
      </div>

      <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter by tier">
        {[
          ["", "All tiers"],
          ["1", "Tier 1"],
          ["2", "Tier 2"],
          ["3", "Tier 3"],
        ].map(([value, label]) => (
          <Link
            key={label}
            href={tierHref(value!)}
            aria-current={tier === value ? "true" : undefined}
            className={
              tier === value
                ? "rounded-full border border-ink bg-ink px-2.5 py-1 text-[12.5px] text-bg"
                : "rounded-full border border-rule-2 px-2.5 py-1 text-[12.5px] text-ink-2 hover:text-ink"
            }
          >
            {label}
          </Link>
        ))}
      </div>

      <Link
        href="/register"
        className="rounded-md px-3 py-1.5 text-[14px] font-medium"
        style={{ background: "var(--accent)", color: "var(--bg)" }}
      >
        Register a service
      </Link>
    </div>
  );
}
