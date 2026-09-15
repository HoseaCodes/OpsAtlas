import type { Route } from "next";
import Link from "next/link";
import { ApiError, type HealthSummary, type ServiceSummary } from "@opsatlas/contracts";
import { CatalogFilters } from "@/components/CatalogFilters";
import { HealthMeter, toHealthState } from "@/components/HealthMeter";
import { PartialFailure } from "@/components/states";
import { Ribbon } from "@/components/Ribbon";
import { Empty, ErrorState } from "@/components/states";
import { controlPlane, noStore } from "@/lib/api";
import { tierLabel } from "@/lib/format";

export const dynamic = "force-dynamic";

type Search = { q?: string; tier?: string; cursor?: string };

export default async function CatalogPage({ searchParams }: { searchParams: Promise<Search> }) {
  const params = await searchParams;

  const api = await controlPlane();

  let page: { items: ServiceSummary[]; nextCursor: string | null };
  try {
    page = await api.listServices({ cursor: params.cursor, limit: 25 }, noStore);
  } catch (error) {
    const problem = error instanceof ApiError ? error : null;
    return (
      <ErrorState
        title="The catalog could not be loaded"
        detail={
          problem?.detail ??
          "The control plane did not respond. Check that it is running: make dev."
        }
        correlationId={problem?.correlationId}
      />
    );
  }

  // Health is a second request, joined here by service id. That is the cost of
  // keeping catalog and operations acyclic (ADR 0010), and the benefit is
  // visible right below: if this read fails, the catalog still lists every
  // service and the page says health is missing rather than showing nothing.
  let health: HealthSummary | null = null;
  let healthError: unknown = null;
  if (page.items.length > 0) {
    const settled = await Promise.allSettled([
      api.getHealthSummary(page.items.map((service) => service.id), noStore),
    ]);
    const result = settled[0]!;
    if (result.status === "fulfilled") health = result.value;
    else healthError = result.reason;
  }

  const statuses: Record<string, string> = health?.statuses ?? {};

  // Search and tier filtering are applied here rather than in the API because
  // the control plane does not offer them yet. That is a real limitation and it
  // has a real consequence: filtering applies to the current page only, and the
  // UI says so rather than implying a fleet-wide search.
  const filtered = page.items.filter((service) => {
    if (params.tier && String(service.tier) !== params.tier) return false;
    if (params.q) {
      const haystack = `${service.slug} ${service.displayName ?? ""} ${service.repository} ${service.runtime ?? ""}`;
      if (!haystack.toLowerCase().includes(params.q.toLowerCase())) return false;
    }
    return true;
  });

  const unowned = page.items.filter((service) => !service.owned).length;
  const unhealthy = page.items.filter((service) => {
    const status = statuses[service.id];
    return status === "DOWN" || status === "DEGRADED";
  }).length;

  return (
    <>
      <header className="border-b border-rule px-4 pt-6 md:px-6">
        <h1 className="max-w-[26ch] text-[clamp(21px,2.6vw,31px)] font-semibold leading-[1.22] tracking-[-0.022em]">
          <span className="mono font-medium tracking-[-0.03em]">{page.items.length}</span>
          {page.items.length === 1 ? " service registered" : " services registered"}
          {unhealthy > 0 ? (
            <>
              , <span className="mono font-medium tracking-[-0.03em]">{unhealthy}</span> not healthy
            </>
          ) : null}
          {unowned > 0 ? (
            <>
              , <span className="mono font-medium tracking-[-0.03em]">{unowned}</span> unowned.
            </>
          ) : (
            "."
          )}
        </h1>
        <p className="mt-2.5 max-w-[70ch] text-[13.5px] text-ink-2">
          Every entry was read from a <span className="mono">service.yaml</span> submitted by the owning
          team. Nothing here is maintained by hand.{" "}
          <span className="text-ink-3">
            Health comes from probing a declared health endpoint on an interval. A service with no reading
            has never been probed, which is not the same as being healthy.
          </span>
        </p>

        <CatalogFilters />
      </header>

      {healthError ? (
        <div className="px-4 pt-4 md:px-6">
          <PartialFailure
            what="Health"
            detail={
              healthError instanceof ApiError
                ? healthError.detail
                : "The health read did not complete."
            }
            correlationId={healthError instanceof ApiError ? healthError.correlationId : undefined}
          />
        </div>
      ) : null}

      {filtered.length === 0 ? (
        page.items.length === 0 ? (
          <Empty title="No services are registered yet.">
            Register one by submitting its <span className="mono">service.yaml</span>. There are six example
            manifests in <span className="mono">examples/services/</span> to try.{" "}
            <Link href="/register" className="underline" style={{ color: "var(--accent)" }}>
              Register a service
            </Link>
            .
          </Empty>
        ) : (
          <Empty title="Nothing on this page matches that filter.">
            Filtering applies to the services on this page only — the control plane does not offer
            server-side search yet, so a match on a later page will not be found.
          </Empty>
        )
      ) : (
        <section aria-label="Registered services">
          <div
            className="hidden grid-cols-[minmax(0,1fr)_140px_110px_78px] gap-3 border-b border-rule px-6 py-2.5
                       text-[11.5px] text-ink-3 md:grid"
            aria-hidden="true"
          >
            <span>Service and owner</span>
            <span>Health</span>
            <span>30 days</span>
            <span className="text-right">Tier</span>
          </div>

          <ul className="list-none p-0">
            {filtered.map((service) => (
              <li key={service.id}>
                <Link
                  href={`/catalog/${service.slug}` as Route}
                  className="grid grid-cols-1 items-center gap-2 border-b border-rule px-4 py-3
                             hover:bg-surface md:grid-cols-[minmax(0,1fr)_140px_110px_78px] md:gap-3 md:px-6"
                >
                  <span className="flex min-w-0 flex-col gap-0.5">
                    <b className="truncate font-medium tracking-[-0.008em]">
                      {service.displayName ?? service.slug}
                    </b>
                    <span className="truncate text-[12px] text-ink-2">
                      <span className="mono">{service.slug}</span>
                      {" · "}
                      {service.owned ? "owned" : <span style={{ color: "var(--alarm)" }}>no owner</span>}
                      {service.runtime ? ` · ${service.runtime}` : null}
                    </span>
                  </span>

                  <span className="md:block">
                    {/* Absent from the map means never probed, which the meter
                        renders as an outline rather than as healthy. */}
                    <HealthMeter state={toHealthState(statuses[service.id])} />
                  </span>

                  <span className="hidden md:block">
                    {/* The list carries no daily history - that is a per-environment
                        read, and the detail page is where it belongs. */}
                    <Ribbon />
                  </span>

                  <span className="mono text-[12.5px] md:text-right" title={tierLabel(service.tier)}>
                    {service.tier}
                  </span>
                </Link>
              </li>
            ))}
          </ul>

          {page.nextCursor ? (
            <div className="px-6 py-4">
              <Link
                href={`/catalog?cursor=${encodeURIComponent(page.nextCursor)}` as Route}
                className="inline-block rounded-md border border-rule-2 px-3 py-1.5 text-[13px] text-ink-2 hover:border-ink-3 hover:text-ink"
              >
                Next page →
              </Link>
            </div>
          ) : null}
        </section>
      )}
    </>
  );
}
