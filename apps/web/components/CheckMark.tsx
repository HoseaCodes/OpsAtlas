export type CheckStatus = "PASS" | "FAIL" | "NOT_APPLICABLE";

/**
 * A scorecard verdict as a shape.
 *
 * Pass is a thin stroked check; fail is a filled square; not-applicable is a
 * short rule. The weight difference - stroke against fill - is what carries the
 * signal, so the grid stays readable in greyscale, in print, and for a
 * colour-blind reader. Colour on a failure reinforces; it never carries.
 */
export function CheckMark({ status }: { status: CheckStatus }) {
  if (status === "PASS") {
    return (
      <svg className="mark" width="13" height="13" viewBox="0 0 13 13" role="img" aria-label="Passing">
        <path d="M2.4 7l2.7 2.7L10.8 4" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      </svg>
    );
  }

  if (status === "FAIL") {
    return (
      <svg className="mark mark--fail" width="13" height="13" viewBox="0 0 13 13" role="img" aria-label="Failing">
        <rect x="1.5" y="1.5" width="10" height="10" rx="2" fill="currentColor" />
        <rect x="6" y="3.6" width="1.2" height="4.2" fill="var(--surface)" />
        <rect x="6" y="8.4" width="1.2" height="1.2" fill="var(--surface)" />
      </svg>
    );
  }

  return (
    <svg className="mark" width="13" height="13" viewBox="0 0 13 13" role="img" aria-label="Not applicable">
      <line x1="3" y1="6.5" x2="10" y2="6.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" opacity="0.55" />
    </svg>
  );
}
