import type { DailyAvailability } from "@opsatlas/contracts";

/**
 * Thirty days of probe availability, one bar per day.
 *
 * Height encodes the day, never hue: full for a day inside target, shorter as
 * availability falls, and an outline for a day with no probes at all. A reader
 * converting the page to greyscale still sees the shape of a bad fortnight.
 *
 * Days with no data are outlines rather than zero-height bars. A day nobody
 * probed is not a day the service was down, and drawing them the same would
 * make a newly watched service look like it had been broken for a month.
 */
export function Ribbon({ days = [], window = 30 }: { days?: DailyAvailability[]; window?: number }) {
  const byDay = new Map(days.map((day) => [day.day, day]));
  const cells = lastDays(window).map((date) => ({ date, day: byDay.get(date) }));

  const observed = days.length;
  const label =
    observed === 0
      ? "No daily history: this environment has never been probed."
      : `Probe availability for the last ${window} days. ${observed} ${observed === 1 ? "day has" : "days have"} been probed.`;

  return (
    <span className="ribbon" role="img" aria-label={label}>
      {cells.map(({ date, day }) => (
        <i key={date} data-value={value(day)} title={describe(date, day)} />
      ))}
    </span>
  );
}

function value(day?: DailyAvailability): "ok" | "warn" | "bad" | "none" {
  if (!day || day.probes === 0) return "none";
  // Thresholds, not a gradient: the ribbon answers "was this day fine, wobbly
  // or bad", and three heights are legible at 4px where a continuous scale is
  // not.
  if (day.availability >= 0.999) return "ok";
  if (day.availability >= 0.95) return "warn";
  return "bad";
}

function describe(date: string, day?: DailyAvailability): string {
  if (!day || day.probes === 0) return `${date}: not probed`;
  const percent = (day.availability * 100).toFixed(day.availability === 1 ? 0 : 2);
  const latency = day.meanResponseMs === null ? "" : `, mean ${day.meanResponseMs}ms`;
  return `${date}: ${percent}% of ${day.probes} probes succeeded${latency}`;
}

/** The window ending today, oldest first, in UTC to match how days are counted. */
function lastDays(window: number): string[] {
  const today = new Date();
  return Array.from({ length: window }, (_, index) => {
    const date = new Date(
      Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() - (window - 1 - index)),
    );
    return date.toISOString().slice(0, 10);
  });
}
