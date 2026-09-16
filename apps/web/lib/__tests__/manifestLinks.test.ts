import { describe, expect, it } from "vitest";
import {
  dashboardUrl,
  dependencyList,
  journeyList,
  observabilityServiceName,
  coverageLabel,
  oncall,
  operationsContact,
  runbookRef,
  sloTarget,
} from "../manifestLinks";

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

describe("reading the runbook", () => {
  const withRunbook = (runbook: unknown) => ({ spec: { operations: { runbook } } });

  it("returns an https URL as a link", () => {
    expect(runbookRef(withRunbook("https://wiki.example.com/runbooks/orders"))).toEqual({
      kind: "url",
      href: "https://wiki.example.com/runbooks/orders",
    });
  });

  it("returns a repository-relative path as a path, not a link", () => {
    // The schema permits docs/runbook.md. Turning that into a github.com URL
    // would invent a host the manifest never named.
    expect(runbookRef(withRunbook("docs/runbook.md"))).toEqual({ kind: "path", path: "docs/runbook.md" });
  });

  it("never returns a dangerous scheme as a link", () => {
    // Falls through to `path`, which renders as text. The point is that no
    // value other than an https URL can reach an href.
    expect(runbookRef(withRunbook("javascript:alert(1)"))).toEqual({
      kind: "path",
      path: "javascript:alert(1)",
    });
    expect(runbookRef(withRunbook("http://wiki.example.com"))).toEqual({
      kind: "path",
      path: "http://wiki.example.com",
    });
  });

  it("returns nothing when absent, blank, or not a string", () => {
    expect(runbookRef(withRunbook(undefined))).toBeUndefined();
    expect(runbookRef(withRunbook("   "))).toBeUndefined();
    expect(runbookRef(withRunbook(42))).toBeUndefined();
    expect(runbookRef({})).toBeUndefined();
    expect(runbookRef(null)).toBeUndefined();
  });
});

describe("reading the operations contact", () => {
  it("returns a declared channel", () => {
    expect(operationsContact({ spec: { operations: { contact: "#orders-eng" } } })).toBe("#orders-eng");
  });

  it("returns nothing when absent or blank", () => {
    expect(operationsContact({ spec: { operations: {} } })).toBeUndefined();
    expect(operationsContact({ spec: { operations: { contact: "  " } } })).toBeUndefined();
    expect(operationsContact(undefined)).toBeUndefined();
  });
});

describe("reading the SLO target", () => {
  const withSlo = (slo: unknown) => ({ spec: { operations: { slo } } });

  it("returns both halves of a declared target", () => {
    expect(sloTarget(withSlo({ availability: 99.9, window: "30d" }))).toEqual({
      availability: 99.9,
      window: "30d",
    });
  });

  it("returns nothing when either half is missing", () => {
    // Half a target renders as a measurement. It is not one.
    expect(sloTarget(withSlo({ availability: 99.9 }))).toBeUndefined();
    expect(sloTarget(withSlo({ window: "30d" }))).toBeUndefined();
    expect(sloTarget(withSlo({}))).toBeUndefined();
    expect(sloTarget({})).toBeUndefined();
  });

  it("refuses an availability that is not a finite number", () => {
    expect(sloTarget(withSlo({ availability: "99.9", window: "30d" }))).toBeUndefined();
    expect(sloTarget(withSlo({ availability: Number.NaN, window: "30d" }))).toBeUndefined();
  });
});

describe("reading the dependency list", () => {
  it("distinguishes an absent key from an empty list", () => {
    // The whole reason the schema keeps both shapes, and what
    // DependenciesDeclaredCheck rewards.
    expect(dependencyList({ spec: {} })).toEqual({ stated: false, entries: [] });
    expect(dependencyList({ spec: { dependencies: [] } })).toEqual({ stated: true, entries: [] });
  });

  it("normalizes the string shorthand to kind service", () => {
    expect(dependencyList({ spec: { dependencies: ["payments-api"] } })).toEqual({
      stated: true,
      entries: [{ name: "payments-api", kind: "service" }],
    });
  });

  it("keeps a declared kind and defaults an unknown one to service", () => {
    expect(
      dependencyList({
        spec: {
          dependencies: [
            { name: "postgres", kind: "datastore" },
            { name: "stripe", kind: "external" },
            { name: "odd", kind: "nonsense" },
          ],
        },
      }),
    ).toEqual({
      stated: true,
      entries: [
        { name: "postgres", kind: "datastore" },
        { name: "stripe", kind: "external" },
        { name: "odd", kind: "service" },
      ],
    });
  });

  it("drops entries with no usable name rather than rendering a blank chip", () => {
    expect(dependencyList({ spec: { dependencies: ["", "  ", {}, { name: 7 }, null, ["x"]] } })).toEqual({
      stated: true,
      entries: [],
    });
  });

  it("survives a manifest shaped nothing like a manifest", () => {
    expect(dependencyList({ spec: { dependencies: "postgres" } })).toEqual({ stated: false, entries: [] });
    expect(dependencyList(null)).toEqual({ stated: false, entries: [] });
  });
});

describe("reading the journey list", () => {
  it("returns declared journeys", () => {
    expect(journeyList({ spec: { journeys: ["Checkout", "Refunds"] } })).toEqual(["Checkout", "Refunds"]);
  });

  it("treats an empty list as not declared, matching the scorecard check", () => {
    expect(journeyList({ spec: { journeys: [] } })).toEqual([]);
    expect(journeyList({ spec: {} })).toEqual([]);
    expect(journeyList(undefined)).toEqual([]);
  });

  it("drops blank and non-string entries", () => {
    expect(journeyList({ spec: { journeys: ["Checkout", "", "   ", 42, null] } })).toEqual(["Checkout"]);
  });
});

describe("reading the on-call declaration", () => {
  const withOncall = (value: unknown) => ({ spec: { operations: { oncall: value } } });

  it("returns the rotation, coverage and escalation", () => {
    expect(
      oncall(
        withOncall({
          rotation: "https://pagerduty.example.com/schedules/P1",
          coverage: "24x7",
          escalation: "platform-leads",
        }),
      ),
    ).toEqual({
      rotation: "https://pagerduty.example.com/schedules/P1",
      coverage: "24x7",
      escalation: "platform-leads",
    });
  });

  it("refuses a rotation that is not an https URL", () => {
    // Same rule as every other link read out of somebody else's document.
    expect(oncall(withOncall({ rotation: "javascript:alert(1)", coverage: "24x7" }))?.rotation).toBeUndefined();
    expect(oncall(withOncall({ rotation: "http://pager.example.com", coverage: "24x7" }))?.rotation).toBeUndefined();
  });

  it("drops a coverage value outside the three the schema allows", () => {
    expect(oncall(withOncall({ rotation: "https://x.example.com", coverage: "sometimes" }))?.coverage)
      .toBeUndefined();
  });

  it("treats an empty block as no declaration at all", () => {
    // Otherwise `oncall: {}` renders a section claiming something was declared.
    expect(oncall(withOncall({}))).toBeUndefined();
    expect(oncall(withOncall({ rotation: "   " }))).toBeUndefined();
  });

  it("returns nothing when absent or shaped wrongly", () => {
    expect(oncall({ spec: { operations: {} } })).toBeUndefined();
    expect(oncall(withOncall("business-hours"))).toBeUndefined();
    expect(oncall(withOncall([]))).toBeUndefined();
    expect(oncall(null)).toBeUndefined();
  });
});

describe("labelling coverage", () => {
  it("says what each value means for whether anyone is woken", () => {
    expect(coverageLabel("24x7")).toContain("around the clock");
    expect(coverageLabel("business-hours")).toContain("business hours");
    expect(coverageLabel("best-effort")).toContain("nobody paged");
  });
});
