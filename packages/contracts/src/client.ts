import type { components } from "./generated/api";

/**
 * A typed fetch wrapper over the control plane.
 *
 * CLAUDE.md §5 forbids hand-writing API *types* that the generator can produce.
 * Every type below is `components["schemas"][…]` from the generated document;
 * the only hand-written part is the call itself. ADR 0005 records why a ~70-line
 * wrapper was preferred to a fully generated client for a five-endpoint surface.
 */

export type ServiceSummary = components["schemas"]["ServiceSummary"];
export type ServiceDetail = components["schemas"]["ServiceDetail"];
export type Scorecard = components["schemas"]["Scorecard"];
export type CheckResult = components["schemas"]["CheckResult"];
export type AuditEntry = components["schemas"]["AuditEntry"];
export type PolicyRules = components["schemas"]["PolicyRules"];
export type EnvironmentView = components["schemas"]["EnvironmentView"];
export type SourceView = components["schemas"]["SourceView"];
export type EnvironmentHealth = components["schemas"]["EnvironmentHealth"];
export type DailyAvailability = components["schemas"]["DailyAvailability"];
export type HealthSummary = components["schemas"]["HealthSummary"];
export type ServiceHealth = components["schemas"]["ServiceHealth"];

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

/** One thing wrong with a submitted document, located precisely enough to fix. */
export interface Violation {
  pointer: string;
  keyword: string;
  expected?: string | null;
  found?: string | null;
  message: string;
}

/**
 * An RFC 9457 problem response.
 *
 * The control plane returns this shape for every failure, so the console never
 * has to guess why something went wrong — and `correlationId` is what turns
 * "it broke" into a log search.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly type: string;
  readonly title: string;
  readonly detail: string;
  readonly correlationId: string;
  readonly violations: Violation[];

  constructor(status: number, body: Record<string, unknown>) {
    const detail = typeof body.detail === "string" ? body.detail : "The request failed.";
    super(detail);
    this.name = "ApiError";
    this.status = status;
    this.type = typeof body.type === "string" ? body.type : "about:blank";
    this.title = typeof body.title === "string" ? body.title : "Request failed";
    this.detail = detail;
    this.correlationId = typeof body.correlationId === "string" ? body.correlationId : "unknown";
    this.violations = Array.isArray(body.violations) ? (body.violations as Violation[]) : [];
  }

  /** True when the failure was the submitted manifest rather than the request. */
  get isValidationFailure(): boolean {
    return this.status === 422 || this.violations.length > 0;
  }
}

export interface ClientOptions {
  baseUrl?: string;
  /** Passed through so a caller can cancel, and so Next.js can control caching. */
  fetch?: typeof globalThis.fetch;
}

export class OpsAtlasClient {
  private readonly baseUrl: string;
  private readonly doFetch: typeof globalThis.fetch;

  constructor(options: ClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? "http://localhost:8080").replace(/\/$/, "");
    this.doFetch = options.fetch ?? globalThis.fetch;
  }

  listServices(params: { cursor?: string; limit?: number } = {}, init?: RequestInit) {
    const query = new URLSearchParams();
    if (params.cursor) query.set("cursor", params.cursor);
    if (params.limit) query.set("limit", String(params.limit));
    return this.request<Page<ServiceSummary>>(`/api/v1/services?${query}`, init);
  }

  getService(slug: string, init?: RequestInit) {
    return this.request<ServiceDetail>(`/api/v1/services/${encodeURIComponent(slug)}`, init);
  }

  getScorecard(slug: string, init?: RequestInit) {
    return this.request<Scorecard>(`/api/v1/services/${encodeURIComponent(slug)}/scorecard`, init);
  }

  getPolicyRules(init?: RequestInit) {
    return this.request<PolicyRules>("/api/v1/policy/rules", init);
  }

  listAuditEvents(params: { cursor?: string; limit?: number } = {}, init?: RequestInit) {
    const query = new URLSearchParams();
    if (params.cursor) query.set("cursor", params.cursor);
    if (params.limit) query.set("limit", String(params.limit));
    return this.request<Page<AuditEntry>>(`/api/v1/audit-events?${query}`, init);
  }

  /**
   * Worst status per service, for the catalog list.
   *
   * Served separately from the catalog so the two modules stay acyclic
   * (ADR 0010). The practical benefit here: a slow or failing health read does
   * not slow or fail the service list, and the page says which half is missing.
   *
   * A service absent from `statuses` has never been probed - which is not the
   * same as being healthy.
   */
  getHealthSummary(serviceIds: string[], init?: RequestInit) {
    const query = new URLSearchParams();
    for (const id of serviceIds) query.append("serviceIds", id);
    return this.request<HealthSummary>(`/api/v1/health?${query}`, init);
  }

  /** Per-environment state and daily history for one service. */
  getServiceHealth(slug: string, init?: RequestInit) {
    return this.request<ServiceHealth>(`/api/v1/services/${encodeURIComponent(slug)}/health`, init);
  }

  /** Repositories OpsAtlas watches. Read-only against the provider; see ADR 0008. */
  listSources(params: { cursor?: string; limit?: number } = {}, init?: RequestInit) {
    const query = new URLSearchParams();
    if (params.cursor) query.set("cursor", params.cursor);
    if (params.limit) query.set("limit", String(params.limit));
    return this.request<Page<SourceView>>(`/api/v1/sources?${query}`, init);
  }

  getSource(id: string, init?: RequestInit) {
    return this.request<SourceView>(`/api/v1/sources/${encodeURIComponent(id)}`, init);
  }

  /** Register a service. The body is the raw service.yaml, not a JSON envelope. */
  registerService(document: string, params: { sourcePath?: string; sourceRef?: string } = {}) {
    const query = new URLSearchParams();
    if (params.sourcePath) query.set("sourcePath", params.sourcePath);
    if (params.sourceRef) query.set("sourceRef", params.sourceRef);
    return this.request<ServiceDetail>(`/api/v1/services?${query}`, {
      method: "POST",
      headers: { "Content-Type": "application/yaml" },
      body: document,
    });
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await this.doFetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: { Accept: "application/json", ...(init?.headers ?? {}) },
    });

    if (!response.ok) {
      // Every failure is a problem document, but a proxy or a crash can still
      // produce something else. Falling back to a synthetic body keeps the
      // caller's error handling uniform rather than making it handle two shapes.
      let body: Record<string, unknown>;
      try {
        body = (await response.json()) as Record<string, unknown>;
      } catch {
        body = { detail: `The control plane returned ${response.status} with no problem document.` };
      }
      throw new ApiError(response.status, body);
    }

    return (await response.json()) as T;
  }
}
