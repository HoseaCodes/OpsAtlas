export type HealthState = "healthy" | "degraded" | "down" | "unobserved";

/**
 * Three notches whose heights encode health.
 *
 * The meter is aria-hidden and always accompanied by its text label, so the
 * signal reaches a screen reader as words rather than as decoration.
 *
 * In slice one every registered service is `unobserved`: nothing probes
 * anything until the observer arrives (phase 7). That state renders as an
 * outline rather than a short filled bar, because a short bar would read as a
 * low measurement and there is no measurement.
 */
export function HealthMeter({ state }: { state: HealthState }) {
  return (
    <span className="inline-flex items-center gap-2 text-[12.5px] text-ink-2">
      <span className="meter" data-state={state} aria-hidden="true">
        <i />
        <i />
        <i />
      </span>
      <span>{label(state)}</span>
    </span>
  );
}

function label(state: HealthState): string {
  switch (state) {
    case "healthy":
      return "Healthy";
    case "degraded":
      return "Degraded";
    case "down":
      return "Down";
    default:
      return "Never observed";
  }
}
