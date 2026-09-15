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
