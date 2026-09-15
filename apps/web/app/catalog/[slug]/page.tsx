import type { Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import {
  ApiError,
  type EnvironmentHealth,
  type Scorecard,
  type ServiceDetail,
  type ServiceHealth,
} from "@opsatlas/contracts";
import { CheckMark, type CheckStatus } from "@/components/CheckMark";
import { HealthMeter, toHealthState } from "@/components/HealthMeter";
import { NotObservedBadge } from "@/components/MockedBadge";
import { Ribbon } from "@/components/Ribbon";
import { ServiceAdmin } from "@/components/ServiceAdmin";
import { ServiceTabs } from "@/components/ServiceTabs";
import { ErrorState, NotYetMeasured, PartialFailure } from "@/components/states";
import { controlPlane, noStore } from "@/lib/api";
import { absoluteTime, relativeTime, tierLabel } from "@/lib/format";
import { dashboardUrl, observabilityServiceName } from "@/lib/manifestLinks";

export const dynamic = "force-dynamic";

type Params = { slug: string };
type Search = { tab?: string };

export default async function ServicePage({
  params,
  searchParams,
}: {
  params: Promise<Params>;
  searchParams: Promise<Search>;
}) {
  const { slug } = await params;
  const { tab = "overview" } = await searchParams;

  // The two requests are issued together and settled independently. That is the
  // partial-failure case CLAUDE.md §10 calls the normal case for a control
  // plane: if the scorecard cannot be read, the service detail is still worth
  // showing, and the page says which half is missing instead of failing whole.
  const api = await controlPlane();
  const [detailResult, scorecardResult, healthResult] = await Promise.allSettled([
    api.getService(slug, noStore),
    api.getScorecard(slug, noStore),
    api.getServiceHealth(slug, noStore),
  ]);

  if (detailResult.status === "rejected") {
    const problem = detailResult.reason;
    if (problem instanceof ApiError && problem.status === 404) {
      notFound();
    }
    return (
      <ErrorState
        title="This service could not be loaded"
        detail={
          problem instanceof ApiError
            ? problem.detail
            : "The control plane did not respond. Check that it is running: make dev."
        }
        correlationId={problem instanceof ApiError ? problem.correlationId : undefined}
      />
    );
  }

  const service: ServiceDetail = detailResult.value;
  const scorecard: Scorecard | null = scorecardResult.status === "fulfilled" ? scorecardResult.value : null;
  const scorecardError = scorecardResult.status === "rejected" ? scorecardResult.reason : null;

  const health: ServiceHealth | null = healthResult.status === "fulfilled" ? healthResult.value : null;
  const healthError = healthResult.status === "rejected" ? healthResult.reason : null;

  // Keyed by environment so the overview can join without a second pass.
  // Absent means this environment has never been probed.
  const healthByEnvironment = new Map<string, EnvironmentHealth>(
    (health?.environments ?? []).map((entry) => [entry.environmentId, entry]),
  );

  // The header shows the worst environment, the same rollup the list uses: a
  // service whose production is down is down, whatever staging is doing.
  const worst = worstStatus(health);

  return (
    <>
      <div className="px-4 pt-5 md:px-6">
        <Link href="/catalog" className="text-[13px] text-ink-2 hover:text-ink">
          ← All services
        </Link>

        <div className="mt-3 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[20px] font-semibold tracking-[-0.02em]">
              {service.displayName ?? service.slug}
            </h1>
            <p className="mt-1 text-[13px] text-ink-2">
              <span className="mono">{service.slug}</span> · tier {service.tier} ({tierLabel(service.tier)})
              {service.runtime ? ` · ${service.runtime}` : null}
            </p>
          </div>
          <div className="flex items-center gap-2.5">
            <HealthMeter state={toHealthState(worst)} />
            {health?.neverObserved !== false ? <NotObservedBadge /> : null}
          </div>
        </div>

        {!service.owner ? (
          <div
            className="mt-3.5 rounded-r-md border border-l-[3px] border-rule-2 bg-surface p-3"
            style={{ borderLeftColor: "var(--alarm)" }}
            role="note"
          >
            <b className="font-medium" style={{ color: "var(--alarm)" }}>
              This service has no owner
            </b>
            <p className="mt-1 text-[13px] text-ink-2">
              No <span className="mono">metadata.owner</span> is declared, so nobody is accountable for it
              and no alert could be routed to a rotation.
            </p>
          </div>
        ) : null}
      </div>

      <ServiceTabs slug={service.slug} active={tab} />

      {tab === "scorecard" ? (
        <ScorecardPane scorecard={scorecard} error={scorecardError} />
      ) : tab === "manifest" ? (
        <ManifestPane service={service} />
      ) : (
        <OverviewPane
          service={service}
          scorecard={scorecard}
          scorecardError={scorecardError}
          health={health}
          healthByEnvironment={healthByEnvironment}
          healthError={healthError}
        />
      )}
    </>
  );
}

/** Worst across environments, or null when none has been probed. */
function worstStatus(health: ServiceHealth | null): string | null {
  if (!health || health.environments.length === 0) return null;
  const order = ["DOWN", "DEGRADED", "HEALTHY"];
  return (
    health.environments
      .map((entry) => entry.status)
      .sort((a, b) => order.indexOf(a) - order.indexOf(b))[0] ?? null
  );
}

function OverviewPane({
  service,
  scorecard,
  scorecardError,
  health,
  healthByEnvironment,
  healthError,
}: {
  service: ServiceDetail;
  scorecard: Scorecard | null;
  scorecardError: unknown;
  health: ServiceHealth | null;
  healthByEnvironment: Map<string, EnvironmentHealth>;
  healthError: unknown;
}) {
  const dashboard = dashboardUrl(service.manifest);
  const telemetryName = observabilityServiceName(service.manifest);

  return (
    <div className="flex flex-col gap-7 px-4 py-5 md:px-6">
      <section>
        <h2 className="mb-2.5 text-[13px] font-semibold">Ownership and source</h2>
        <dl className="grid grid-cols-[minmax(0,150px)_minmax(0,1fr)] gap-x-3.5 gap-y-2 text-[13px]">
          <dt className="text-ink-2">Owner</dt>
          <dd className="mono m-0 text-[12.5px]">
            {service.owner ?? <span style={{ color: "var(--alarm)" }}>none declared</span>}
          </dd>

          <dt className="text-ink-2">Repository</dt>
          <dd className="mono m-0 break-words text-[12.5px]">{service.repository}</dd>

          <dt className="text-ink-2">Lifecycle</dt>
          <dd className="mono m-0 text-[12.5px]">{service.lifecycle}</dd>

          <dt className="text-ink-2">Manifest path</dt>
          <dd className="mono m-0 text-[12.5px]">{service.sourcePath}</dd>

          <dt className="text-ink-2">Schema version</dt>
          <dd className="mono m-0 break-words text-[12.5px]">{service.schemaVersion}</dd>

          <dt className="text-ink-2">Manifest digest</dt>
          <dd className="mono m-0 break-all text-[12.5px]" title={service.manifestDigest}>
            {service.manifestDigest.slice(0, 16)}…
          </dd>

          <dt className="text-ink-2">Registered</dt>
          <dd className="m-0 text-[12.5px]" title={absoluteTime(service.registeredAt)}>
            {relativeTime(service.registeredAt)}
          </dd>
        </dl>
      </section>

      <section>
        <h2 className="mb-2.5 text-[13px] font-semibold">Observability</h2>
        <dl className="grid grid-cols-[minmax(0,150px)_minmax(0,1fr)] gap-x-3.5 gap-y-2 text-[13px]">
          <dt className="text-ink-2">Dashboard</dt>
          <dd className="m-0 break-words text-[12.5px]">
            {dashboard ? (
              <a
                href={dashboard}
                // A manifest is somebody else's document. noreferrer keeps this
                // console's URLs out of their referrer log, and noopener stops
                // the opened page reaching back into this window.
                target="_blank"
                rel="noreferrer noopener external"
                className="mono underline"
                style={{ color: "var(--accent)" }}
              >
                {dashboard}
              </a>
            ) : (
              <span className="text-ink-3">
                none declared — add <span className="mono">spec.observability.dashboard</span>
              </span>
            )}
          </dd>

          <dt className="text-ink-2">Telemetry name</dt>
          {/* The class moves rather than nesting an override: a declared name is
              an identifier and belongs in mono, an absence is prose and does not. */}
          {telemetryName ? (
            <dd className="mono m-0 text-[12.5px]">{telemetryName}</dd>
          ) : (
            <dd className="m-0 text-[12.5px] text-ink-3">
              none declared, so traces cannot be joined to this entry
            </dd>
          )}
        </dl>
        <p className="mt-2 max-w-prose text-[12.5px] text-ink-3">
          Both are declarations by the owning team, not something OpsAtlas verified. Nothing here
          checks that the dashboard resolves or that telemetry arrives under that name.
        </p>
      </section>

      <ServiceAdmin
        slug={service.slug}
        version={service.version}
        manifest={service.manifest}
        lifecycle={service.lifecycle}
      />

      <section>
        <h2 className="mb-2.5 text-[13px] font-semibold">Environments</h2>
        <table className="plain">
          <thead>
            <tr>
              <th>Environment</th>
              <th>Health</th>
              <th>URL</th>
              <th>Probe availability</th>
              <th>Last probed</th>
            </tr>
          </thead>
          <tbody>
            {service.environments.map((environment) => {
              const state = healthByEnvironment.get(environment.id);
              return (
                <tr key={environment.id}>
                  <td className="mono text-[12.5px]">{environment.name}</td>
                  <td>
                    <HealthMeter state={toHealthState(state?.status)} detail={state?.detail} />
                  </td>
                  <td className="mono break-all text-[12.5px]">
                    {environment.url ?? <span className="text-ink-3">none declared</span>}
                  </td>
                  <td className="mono text-[12.5px]">
                    {state?.probeAvailability == null ? (
                      // A blank cell reads as a rendering bug; a label reads as
                      // a measurement that has not been taken.
                      <NotYetMeasured>not probed</NotYetMeasured>
                    ) : (
                      `${(state.probeAvailability * 100).toFixed(2)}%`
                    )}
                  </td>
                  <td className="text-[12.5px]">
                    {state ? (
                      <span title={absoluteTime(state.lastProbeAt)}>{relativeTime(state.lastProbeAt)}</span>
                    ) : (
                      <NotYetMeasured>never</NotYetMeasured>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>

      <section>
        <h2 className="mb-2.5 text-[13px] font-semibold">Last 30 days</h2>

        {healthError ? (
          <PartialFailure
            what="The 30-day history"
            detail={healthError instanceof ApiError ? healthError.detail : "The health read did not complete."}
            correlationId={healthError instanceof ApiError ? healthError.correlationId : undefined}
          />
        ) : (
          <div className="flex flex-col gap-3">
            {service.environments.map((environment) => (
              <div key={environment.id} className="flex flex-wrap items-center gap-3">
                <span className="mono w-[110px] shrink-0 text-[12px] text-ink-2">{environment.name}</span>
                <Ribbon days={healthByEnvironment.get(environment.id)?.days ?? []} />
              </div>
            ))}
          </div>
        )}

        <p className="mt-2.5 max-w-[68ch] text-[12.5px] text-ink-2">
          Each bar is one UTC day. Height encodes the share of probes that succeeded; an outlined bar is a
          day nobody probed, which is not the same as a day the service was down.
        </p>
        <p className="mt-1.5 max-w-[68ch] text-[12.5px] text-ink-3">
          {health?.notice ??
            "This is probe availability from one vantage point against a health endpoint, not an SLO."}
        </p>
      </section>

      <section>
        <h2 className="mb-2.5 text-[13px] font-semibold">Scorecard</h2>
        {scorecard ? (
          <p className="text-[13px] text-ink-2">
            <span className="mono text-ink">
              {scorecard.checksPassed}/{scorecard.checksApplicable}
            </span>{" "}
            applicable checks passing.{" "}
            <Link href={`/catalog/${service.slug}?tab=scorecard` as Route} className="underline" style={{ color: "var(--accent)" }}>
              See every check
            </Link>
            .
          </p>
        ) : (
          <PartialFailure
            what="The scorecard"
            detail={
              scorecardError instanceof ApiError
                ? scorecardError.detail
                : "The scorecard request did not complete."
            }
            correlationId={scorecardError instanceof ApiError ? scorecardError.correlationId : undefined}
          />
        )}
      </section>
    </div>
  );
}

function ScorecardPane({ scorecard, error }: { scorecard: Scorecard | null; error: unknown }) {
  if (!scorecard) {
    return (
      <div className="px-4 py-5 md:px-6">
        <PartialFailure
          what="The scorecard"
          detail={error instanceof ApiError ? error.detail : "The scorecard request did not complete."}
          correlationId={error instanceof ApiError ? error.correlationId : undefined}
        />
      </div>
    );
  }

  const checks = scorecard.checks;
  const notApplicable = checks.filter((check) => check.status === "NOT_APPLICABLE").length;

  return (
    <div className="flex flex-col gap-6 px-4 py-5 md:px-6">
      <section>
        <div className="flex flex-wrap items-baseline gap-3">
          <h2 className="text-[13px] font-semibold">
            <span className="mono text-[19px] tracking-[-0.02em]">
              {scorecard.checksPassed} of {scorecard.checksApplicable}
            </span>{" "}
            applicable checks passing
          </h2>
          {notApplicable > 0 ? (
            <span className="text-[12.5px] text-ink-2">
              {notApplicable} {notApplicable === 1 ? "rule does" : "rules do"} not apply at this tier, so they
              are excluded from the total rather than counted as failures.
            </span>
          ) : null}
        </div>
        <p className="mt-1.5 text-[12px] text-ink-3">
          Evaluated {relativeTime(scorecard.evaluatedAt)} against policy set{" "}
          <span className="mono">{scorecard.policySetVersion}</span>.
        </p>
      </section>

      <section>
        <ul className="m-0 list-none p-0">
          {checks.map((check) => (
            <li key={check.checkId} className="flex items-start gap-3 border-b border-rule py-2.5 last:border-b-0">
              <span className="mt-0.5">
                <CheckMark status={check.status as CheckStatus} />
              </span>
              <span className="flex flex-col gap-0.5">
                <b
                  className={check.status === "FAIL" ? "font-medium" : "font-normal"}
                  style={check.status === "FAIL" ? { color: "var(--alarm)" } : undefined}
                >
                  {check.title}
                  {check.status === "NOT_APPLICABLE" ? (
                    <span className="ml-2 font-normal text-[12px] text-ink-3">not applicable at this tier</span>
                  ) : null}
                </b>
                {check.detail ? <span className="text-[12.5px] text-ink-2">{check.detail}</span> : null}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section className="max-w-[68ch] text-[12.5px] text-ink-2">
        <b className="block font-medium text-ink">What these checks do not do</b>
        <p className="mt-1">
          Every rule above reads what this service&apos;s <span className="mono">service.yaml</span>{" "}
          declares. None of them verifies that a runbook link resolves, that telemetry actually arrives, or
          that a health endpoint actually answers. Runtime verification arrives with the observer.
        </p>
      </section>
    </div>
  );
}

function ManifestPane({ service }: { service: ServiceDetail }) {
  return (
    <div className="px-4 py-5 md:px-6">
      <h2 className="mb-2.5 text-[13px] font-semibold">
        Stored manifest <span className="mono font-normal text-ink-2">{service.repository}/{service.sourcePath}</span>
      </h2>
      {/* The normalized document as it was validated and stored, not a
          re-rendering of it. What you read here is what policy was evaluated
          against. */}
      <pre className="yaml">{JSON.stringify(service.manifest, null, 2)}</pre>
      <p className="mt-2.5 max-w-[64ch] text-[12.5px] text-ink-2">
        Shown as JSON because that is how it is stored after validation — the control plane keeps the
        normalized document, not the original file. The digest above identifies the exact bytes that were
        submitted.
      </p>
    </div>
  );
}
