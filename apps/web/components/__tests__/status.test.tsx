import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CheckMark } from "../CheckMark";
import { HealthMeter, toHealthState } from "../HealthMeter";
import { Ribbon } from "../Ribbon";

/**
 * The status primitives, queried by role and accessible name.
 *
 * These assert the property that the whole design rests on: status reaches a
 * reader as shape and as words, never as colour alone. A test that read the
 * fill colour would pass on a page that is unusable in greyscale.
 */
describe("HealthMeter", () => {
  it("names every state in words, not only in bar heights", () => {
    const { rerender } = render(<HealthMeter state="healthy" />);
    expect(screen.getByText("Healthy")).toBeInTheDocument();

    rerender(<HealthMeter state="degraded" />);
    expect(screen.getByText("Degraded")).toBeInTheDocument();

    rerender(<HealthMeter state="down" />);
    expect(screen.getByText("Down")).toBeInTheDocument();
  });

  it("says 'Never observed' rather than inventing a reading", () => {
    // The state every service is in during slice one. It must not render as a
    // low or healthy measurement, because no measurement was taken.
    render(<HealthMeter state="unobserved" />);
    expect(screen.getByText("Never observed")).toBeInTheDocument();
  });

  it("hides the bars from assistive technology, since the label carries the meaning", () => {
    const { container } = render(<HealthMeter state="healthy" />);
    expect(container.querySelector(".meter")).toHaveAttribute("aria-hidden", "true");
  });
});

describe("CheckMark", () => {
  it("gives each verdict an accessible name", () => {
    const { rerender } = render(<CheckMark status="PASS" />);
    expect(screen.getByRole("img", { name: "Passing" })).toBeInTheDocument();

    rerender(<CheckMark status="FAIL" />);
    expect(screen.getByRole("img", { name: "Failing" })).toBeInTheDocument();

    rerender(<CheckMark status="NOT_APPLICABLE" />);
    expect(screen.getByRole("img", { name: "Not applicable" })).toBeInTheDocument();
  });

  it("distinguishes pass from fail by shape, not only by colour", () => {
    // Pass is a stroked path; fail is a filled rect. If these ever became the
    // same shape in two colours, the greyscale test would fail and so does this.
    const pass = render(<CheckMark status="PASS" />).container;
    expect(pass.querySelector("path")).toBeTruthy();
    expect(pass.querySelector("rect")).toBeNull();

    const fail = render(<CheckMark status="FAIL" />).container;
    expect(fail.querySelector("rect")).toBeTruthy();
  });
});

describe("HealthMeter status mapping", () => {
  it("maps the control plane's statuses, and treats anything else as never probed", () => {
    expect(toHealthState("HEALTHY")).toBe("healthy");
    expect(toHealthState("DEGRADED")).toBe("degraded");
    expect(toHealthState("DOWN")).toBe("down");
    // Absent is the common case: a service nobody has probed. It must not fall
    // through to healthy, which is the one wrong answer that looks fine.
    expect(toHealthState(undefined)).toBe("unobserved");
    expect(toHealthState(null)).toBe("unobserved");
  });
});

describe("Ribbon", () => {
  it("describes an absence of history rather than drawing a flat line", () => {
    render(<Ribbon days={[]} />);
    expect(screen.getByRole("img", { name: /never been probed/i })).toBeInTheDocument();
  });

  it("encodes a day's availability as a height, not a colour", () => {
    const { container } = render(
      <Ribbon
        window={3}
        days={[
          { day: today(-2), probes: 100, successes: 100, availability: 1, meanResponseMs: 10, maxResponseMs: 20 },
          { day: today(-1), probes: 100, successes: 97, availability: 0.97, meanResponseMs: 10, maxResponseMs: 20 },
          { day: today(0), probes: 100, successes: 50, availability: 0.5, meanResponseMs: 10, maxResponseMs: 20 },
        ]}
      />,
    );

    const bars = Array.from(container.querySelectorAll(".ribbon i")).map((bar) =>
      bar.getAttribute("data-value"),
    );
    expect(bars).toEqual(["ok", "warn", "bad"]);
  });

  it("draws an un-probed day as an outline, not as a bad day", () => {
    // A day nobody probed is not a day the service was down. Drawing them the
    // same would make a newly watched service look broken for a month.
    const { container } = render(<Ribbon window={2} days={[]} />);
    const bars = Array.from(container.querySelectorAll(".ribbon i")).map((bar) =>
      bar.getAttribute("data-value"),
    );
    expect(bars).toEqual(["none", "none"]);
  });

  it("explains each day on hover, including how many probes it is based on", () => {
    const { container } = render(
      <Ribbon
        window={1}
        days={[{ day: today(0), probes: 96, successes: 94, availability: 94 / 96, meanResponseMs: 31, maxResponseMs: 90 }]}
      />,
    );
    const title = container.querySelector(".ribbon i")?.getAttribute("title") ?? "";
    expect(title).toContain("96 probes");
    expect(title).toContain("31ms");
  });
});

/** A UTC day offset from today, matching how the ribbon lays out its window. */
function today(offset: number): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + offset))
    .toISOString()
    .slice(0, 10);
}
