import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CheckMark } from "../CheckMark";
import { HealthMeter } from "../HealthMeter";
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

describe("Ribbon", () => {
  it("describes an absence of history rather than drawing a flat line", () => {
    render(<Ribbon />);
    expect(screen.getByRole("img", { name: /never been observed/i })).toBeInTheDocument();
  });
});
