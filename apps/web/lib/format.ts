/** Presentation helpers. No policy decisions live here - those are in the control plane. */

export function tierLabel(tier: number): string {
  switch (tier) {
    case 1:
      return "customer-facing, paged";
    case 2:
      return "business hours";
    default:
      return "internal, best effort";
  }
}

/** "3 hours ago", from an ISO-8601 instant. Relative time is what a reader wants here. */
export function relativeTime(iso: string, now: Date = new Date()): string {
  const then = new Date(iso);
  const seconds = Math.round((now.getTime() - then.getTime()) / 1000);
  if (!Number.isFinite(seconds)) return "unknown";
  if (seconds < 60) return "just now";

  const units: [number, Intl.RelativeTimeFormatUnit][] = [
    [60, "minute"],
    [3600, "hour"],
    [86_400, "day"],
    [2_592_000, "month"],
    [31_536_000, "year"],
  ];

  let chosen: [number, Intl.RelativeTimeFormatUnit] = units[0]!;
  for (const unit of units) {
    if (seconds >= unit[0]) chosen = unit;
  }

  const formatter = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  return formatter.format(-Math.round(seconds / chosen[0]), chosen[1]);
}

/** The absolute instant, for a title attribute. Relative time is friendly; absolute is checkable. */
export function absoluteTime(iso: string): string {
  return new Date(iso).toISOString().replace("T", " ").replace(/\.\d+Z$/, " UTC");
}
