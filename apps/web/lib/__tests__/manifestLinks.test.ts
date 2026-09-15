import { describe, expect, it } from "vitest";
import { dashboardUrl, observabilityServiceName } from "../manifestLinks";

const withDashboard = (dashboard: unknown) => ({
  spec: { observability: { dashboard } },
});

describe("reading a dashboard link out of a manifest", () => {
  it("returns a declared https URL", () => {
    expect(dashboardUrl(withDashboard("https://grafana.example.com/d/abc/orders"))).toBe(
      "https://grafana.example.com/d/abc/orders",
    );
  });

  it("returns nothing when the manifest does not declare one", () => {
    expect(dashboardUrl({ spec: { observability: {} } })).toBeUndefined();
    expect(dashboardUrl({ spec: {} })).toBeUndefined();
    expect(dashboardUrl({})).toBeUndefined();
  });

  it("survives a manifest shaped nothing like a manifest", () => {
    // The column only ever holds ingestor output, but a renderer that throws on
    // unexpected JSON takes the whole page down rather than one row.
    expect(dashboardUrl(undefined)).toBeUndefined();
    expect(dashboardUrl(null)).toBeUndefined();
    expect(dashboardUrl("a string")).toBeUndefined();
    expect(dashboardUrl([1, 2, 3])).toBeUndefined();
    expect(dashboardUrl({ spec: "not an object" })).toBeUndefined();
    expect(dashboardUrl({ spec: { observability: [] } })).toBeUndefined();
  });

  it("refuses a javascript: URL", () => {
    // The schema requires ^https:// so this should never reach the console.
    // It is refused here anyway, because "the other layer checks it" is how an
    // href ends up executing a monitored repository's content.
    expect(dashboardUrl(withDashboard("javascript:alert(1)"))).toBeUndefined();
    expect(dashboardUrl(withDashboard("JavaScript:alert(1)"))).toBeUndefined();
  });

  it("refuses other schemes, including plain http", () => {
    expect(dashboardUrl(withDashboard("http://grafana.example.com"))).toBeUndefined();
    expect(dashboardUrl(withDashboard("data:text/html,<script>"))).toBeUndefined();
    expect(dashboardUrl(withDashboard("file:///etc/passwd"))).toBeUndefined();
  });

  it("refuses a relative path, which would resolve against the console itself", () => {
    expect(dashboardUrl(withDashboard("/catalog"))).toBeUndefined();
    expect(dashboardUrl(withDashboard("//evil.example.com"))).toBeUndefined();
  });

  it("refuses a non-string and an empty string", () => {
    expect(dashboardUrl(withDashboard(42))).toBeUndefined();
    expect(dashboardUrl(withDashboard(""))).toBeUndefined();
    expect(dashboardUrl(withDashboard("   "))).toBeUndefined();
    expect(dashboardUrl(withDashboard({ url: "https://x.example.com" }))).toBeUndefined();
  });
});

describe("reading the observability service name", () => {
  it("returns a declared name", () => {
    expect(observabilityServiceName({ spec: { observability: { serviceName: "orders-api" } } })).toBe(
      "orders-api",
    );
  });

  it("returns nothing when absent or blank", () => {
    expect(observabilityServiceName({ spec: { observability: {} } })).toBeUndefined();
    expect(observabilityServiceName({ spec: { observability: { serviceName: "  " } } })).toBeUndefined();
    expect(observabilityServiceName(null)).toBeUndefined();
  });
});
