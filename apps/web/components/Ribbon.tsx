/**
 * Thirty days of SLO attainment, one bar per day.
 *
 * Rendered here only in its unobserved form, because there is no attainment
 * history to draw: the observer is phase 7. The column exists rather than being
 * hidden, so the shape of what is missing is visible instead of implied.
 */
export function Ribbon({ days = 30 }: { days?: number }) {
  return (
    <span className="ribbon" role="img" aria-label={`No daily SLO history: this service has never been observed.`}>
      {Array.from({ length: days }, (_, index) => (
        <i key={index} data-value="none" />
      ))}
    </span>
  );
}
