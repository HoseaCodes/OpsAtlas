import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Empty, ErrorState, Loading, NotYetMeasured, PartialFailure, Stale } from "../states";

describe("the required states", () => {
  it("announces loading to assistive technology", () => {
    render(<Loading label="Loading the service catalog" />);
    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Loading the service catalog")).toBeInTheDocument();
  });

  it("gives an empty state a reason and a next action, not just 'no results'", () => {
    render(<Empty title="No services are registered yet.">Register one by submitting its manifest.</Empty>);
    expect(screen.getByText("No services are registered yet.")).toBeInTheDocument();
    expect(screen.getByText(/Register one/)).toBeInTheDocument();
  });

  it("puts an error in an alert and shows the correlation id", () => {
    // The correlation id is the only thing that turns "it broke" into a log
    // search someone can actually run.
    render(<ErrorState title="The catalog could not be loaded" detail="Upstream timed out." correlationId="abc-123" />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("The catalog could not be loaded");
    expect(alert).toHaveTextContent("abc-123");
  });

  it("says which part failed and that the rest is still current", () => {
    render(<PartialFailure what="The scorecard" detail="The request timed out." correlationId="xyz-9" />);
    const status = screen.getByRole("status");
    expect(status).toHaveTextContent("The scorecard could not be loaded");
    // The point of this state: the page stays usable and says so.
    expect(status).toHaveTextContent("Everything else on this page is current.");
  });

  it("says when stale data was last known good", () => {
    render(<Stale since="4 minutes ago" />);
    expect(screen.getByRole("status")).toHaveTextContent("last read 4 minutes ago");
  });

  it("labels a measurement that does not exist rather than leaving a blank", () => {
    render(<NotYetMeasured>never — no observer</NotYetMeasured>);
    expect(screen.getByText("never — no observer")).toBeInTheDocument();
  });
});
