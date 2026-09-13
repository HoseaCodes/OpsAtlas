import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SyncStatus, isHealthy } from "../SyncStatus";

/**
 * Sync outcomes follow the same rule as every other status in this console:
 * shape first, colour only as reinforcement.
 */
describe("SyncStatus", () => {
  it("names every outcome in words rather than only shading it", () => {
    const cases = [
      ["REGISTERED", "Registered"],
      ["UPDATED", "Updated"],
      ["UNCHANGED", "Unchanged"],
      ["REJECTED", "Manifest rejected"],
      ["NOT_FOUND", "Manifest not found"],
      ["UNAUTHORIZED", "Not authorised"],
      ["RATE_LIMITED", "Rate limited"],
      ["UNREACHABLE", "Could not reach GitHub"],
      ["CONFLICT", "Conflicts with another service"],
    ] as const;

    for (const [outcome, expected] of cases) {
      const { unmount } = render(<SyncStatus outcome={outcome} />);
      expect(screen.getByText(expected)).toBeInTheDocument();
      unmount();
    }
  });

  it("distinguishes a source that has never run from one that failed", () => {
    // These are different situations - one has no information, the other has
    // bad news - and collapsing them would misreport a brand new source as
    // broken.
    const { unmount } = render(<SyncStatus outcome={null} />);
    expect(screen.getByText("Never synced")).toBeInTheDocument();
    unmount();

    render(<SyncStatus outcome="UNREACHABLE" />);
    expect(screen.getByText("Could not reach GitHub")).toBeInTheDocument();
  });

  it("encodes the outcome in the meter's shape, not only in its label", () => {
    const ok = render(<SyncStatus outcome="UNCHANGED" />).container.querySelector(".meter");
    expect(ok).toHaveAttribute("data-state", "healthy");

    const failing = render(<SyncStatus outcome="REJECTED" />).container.querySelector(".meter");
    expect(failing).toHaveAttribute("data-state", "down");

    const never = render(<SyncStatus outcome={null} />).container.querySelector(".meter");
    expect(never).toHaveAttribute("data-state", "unobserved");
  });

  it("treats an unchanged sync as healthy, because nothing changing is the normal case", () => {
    expect(isHealthy("UNCHANGED")).toBe(true);
    expect(isHealthy("REGISTERED")).toBe(true);
    expect(isHealthy("UPDATED")).toBe(true);
    expect(isHealthy("NOT_FOUND")).toBe(false);
    expect(isHealthy(null)).toBe(false);
  });
});
