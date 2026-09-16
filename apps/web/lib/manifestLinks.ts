/**
 * Links read out of a stored manifest.
 *
 * `ServiceDetail` already carries the whole validated manifest as JSON, so
 * nothing here needs an API change — the value was being delivered and thrown
 * away.
 *
 * Every value in a manifest was written by somebody else's repository. The
 * schema already requires `spec.observability.dashboard` to match `^https://`,
 * so this is the second check rather than the only one: a link is rendered only
 * if it parses as a URL and its protocol is `https:`. Without that, a manifest
 * could put `javascript:` in an `href` and the console would honour it —
 * CLAUDE.md §3 rule 4 is about not executing what a monitored repository
 * supplies, and an `href` is a small execution.
 *
 * It cannot vouch for where the link *goes*. A dashboard URL is the declaring
 * team's claim about their own dashboard, and the console presents it as that.
 */

/** Walks an object path without trusting any level of it to exist or be an object. */
function at(root: unknown, path: readonly string[]): unknown {
  let node: unknown = root;
  for (const key of path) {
    if (typeof node !== "object" || node === null || Array.isArray(node)) return undefined;
    node = (node as Record<string, unknown>)[key];
  }
  return node;
}

/** An `https:` URL, or undefined — never a string this module has not checked. */
function httpsUrl(value: unknown): string | undefined {
  if (typeof value !== "string" || value.trim().length === 0) return undefined;
  try {
    // Absolute parse only: a relative path would resolve against the console's
    // own origin, which would make a manifest able to link into this app.
    return new URL(value).protocol === "https:" ? value : undefined;
  } catch {
    return undefined;
  }
}

/** `spec.observability.dashboard`, if it is declared and safe to link to. */
export function dashboardUrl(manifest: unknown): string | undefined {
  return httpsUrl(at(manifest, ["spec", "observability", "dashboard"]));
}

/** `spec.observability.serviceName`, the name telemetry arrives under. */
export function observabilityServiceName(manifest: unknown): string | undefined {
  const value = at(manifest, ["spec", "observability", "serviceName"]);
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

// ---------------------------------------------------------------------------
// The fields that were validated, stored and rendered nowhere.
//
// `spec.operations.runbook`, `spec.operations.contact`, `spec.operations.slo`,
// `spec.dependencies` and `spec.journeys` all survive ingestion into the
// `manifest` column and, until now, reached no reader. Four of them at least
// moved a scorecard check; `contact` moved nothing at all, so declaring a chat
// channel had no observable effect anywhere in the system.
//
// This is the second time this has happened - `spec.observability.dashboard`
// was the first - so the readers live together here, and each has a test that
// fails if it stops being called.
// ---------------------------------------------------------------------------

/** A trimmed non-empty string, or undefined. */
function text(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

/**
 * `spec.operations.runbook`, which the schema permits in two shapes.
 *
 * An `https://` URL is linkable. A repository-relative path such as
 * `docs/runbook.md` is **not** turned into a link: the repository is
 * `owner/name` with no host, and guessing github.com would invent a URL the
 * manifest never gave. It is shown as the path it is, next to the repository
 * it is relative to, which is enough to find the file.
 */
export function runbookRef(manifest: unknown): { kind: "url"; href: string } | { kind: "path"; path: string } | undefined {
  const value = text(at(manifest, ["spec", "operations", "runbook"]));
  if (value === undefined) return undefined;
  const href = httpsUrl(value);
  if (href !== undefined) return { kind: "url", href };
  // Anything not an https URL is treated as a path and rendered as text, so a
  // scheme this module does not vouch for can never reach an href.
  return { kind: "path", path: value };
}

/**
 * `spec.operations.contact` - a chat channel or alias.
 *
 * Deliberately never a link. The schema takes any string, so this is `#channel`
 * as often as an address, and linkifying by guesswork is how `mailto:` ends up
 * pointed at something a monitored repository chose.
 */
export function operationsContact(manifest: unknown): string | undefined {
  return text(at(manifest, ["spec", "operations", "contact"]));
}

/**
 * `spec.operations.slo` - the target the owning team declared.
 *
 * Both halves or nothing: an availability with no window is not a target, and
 * rendering half of one would read as a measurement. Nothing in OpsAtlas
 * measures against this; the caller is responsible for saying so.
 */
export function sloTarget(manifest: unknown): { availability: number; window: string } | undefined {
  const node = at(manifest, ["spec", "operations", "slo"]);
  const availability = at(node, ["availability"]);
  const window = text(at(node, ["window"]));
  if (typeof availability !== "number" || !Number.isFinite(availability) || window === undefined) {
    return undefined;
  }
  return { availability, window };
}

export type DependencyKind = "service" | "datastore" | "external";

/**
 * `spec.dependencies`, normalized from the two shapes the schema allows.
 *
 * `stated` is the distinction the schema exists to keep and the scorecard
 * rewards: an absent key is a question nobody answered, an empty list is
 * somebody saying "this calls nothing". They must not render the same.
 */
export function dependencyList(manifest: unknown): { stated: boolean; entries: { name: string; kind: DependencyKind }[] } {
  const node = at(manifest, ["spec", "dependencies"]);
  if (!Array.isArray(node)) return { stated: false, entries: [] };

  const entries = node.flatMap((item): { name: string; kind: DependencyKind }[] => {
    if (typeof item === "string") {
      const name = text(item);
      return name === undefined ? [] : [{ name, kind: "service" }];
    }
    const name = text(at(item, ["name"]));
    if (name === undefined) return [];
    const kind = at(item, ["kind"]);
    return [
      {
        name,
        kind: kind === "datastore" || kind === "external" ? kind : "service",
      },
    ];
  });

  return { stated: true, entries };
}

/**
 * `spec.journeys` - the customer-visible experiences this service is on.
 *
 * An empty array reads as not declared here, matching `JourneysDeclaredCheck`,
 * which fails an empty list. Unlike dependencies, "this service is on no
 * customer journey" is not a useful statement to have made.
 */
export function journeyList(manifest: unknown): string[] {
  const node = at(manifest, ["spec", "journeys"]);
  if (!Array.isArray(node)) return [];
  return node.map(text).filter((entry): entry is string => entry !== undefined);
}

export type Coverage = "24x7" | "business-hours" | "best-effort";

/**
 * `spec.operations.oncall` - where the rotation lives and what it covers.
 *
 * Never who is on call. That is live state in a paging provider, and a name
 * this console could not refresh would go stale into the one page somebody
 * reads at 03:00 (ADR 0016).
 */
export function oncall(manifest: unknown):
  | { rotation?: string; coverage?: Coverage; escalation?: string }
  | undefined {
  const node = at(manifest, ["spec", "operations", "oncall"]);
  if (typeof node !== "object" || node === null || Array.isArray(node)) return undefined;

  const rotation = httpsUrl(at(node, ["rotation"]));
  const raw = at(node, ["coverage"]);
  const coverage =
    raw === "24x7" || raw === "business-hours" || raw === "best-effort" ? (raw as Coverage) : undefined;
  const escalation = text(at(node, ["escalation"]));

  // An empty block is the same as no block: it declares nothing.
  if (rotation === undefined && coverage === undefined && escalation === undefined) return undefined;
  return { rotation, coverage, escalation };
}

/** How a declared coverage reads to a person, rather than as a wire value. */
export function coverageLabel(coverage: Coverage): string {
  switch (coverage) {
    case "24x7":
      return "24x7 — paged around the clock";
    case "business-hours":
      return "business hours only";
    default:
      return "best effort, nobody paged";
  }
}
