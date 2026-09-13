import { describe, expect, it } from "vitest";
import { absoluteTime, relativeTime, tierLabel } from "./format";

describe("relativeTime", () => {
  const now = new Date("2026-09-12T12:00:00Z");

  it("reads as a human would say it", () => {
    expect(relativeTime("2026-09-12T11:59:30Z", now)).toBe("just now");
    expect(relativeTime("2026-09-12T11:00:00Z", now)).toBe("1 hour ago");
    expect(relativeTime("2026-09-10T12:00:00Z", now)).toBe("2 days ago");
  });

  it("does not throw on an unparseable instant", () => {
    // A malformed timestamp is a bug worth seeing, but not one worth taking the
    // whole page down for.
    expect(relativeTime("not-a-date", now)).toBe("unknown");
  });
});

describe("absoluteTime", () => {
  it("is checkable, unlike the relative form", () => {
    expect(absoluteTime("2026-09-12T12:00:00Z")).toBe("2026-09-12 12:00:00 UTC");
  });
});

describe("tierLabel", () => {
  it("explains what a tier number obliges", () => {
    expect(tierLabel(1)).toBe("customer-facing, paged");
    expect(tierLabel(2)).toBe("business hours");
    expect(tierLabel(3)).toBe("internal, best effort");
  });
});
