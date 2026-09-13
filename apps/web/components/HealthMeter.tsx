export type HealthState = "healthy" | "degraded" | "down" | "unobserved";

/**
 * Three notches whose heights encode health.
 *
 * The meter is aria-hidden and always accompanied by its text label, so the
 * signal reaches a screen reader as words rather than as decoration.
 *
 * `unobserved` renders as an outline rather than a short filled bar, because a
 * short bar reads as a low measurement and there is no measurement. A service
 * nobody has probed and a service that is down are different facts.
 */
export function HealthMeter({ state, detail }: { state: HealthState; detail?: string | null }) {
  return (
    <span
      className="inline-flex items-center gap-2 text-[12.5px] text-ink-2"
      title={detail ?? undefined}
    >
      <span className="meter" data-state={state} aria-hidden="true">
        <i />
        <i />
        <i />
      </span>
      <span style={state === "down" || state === "degraded" ? { color: "var(--alarm)" } : undefined}>
        {label(state)}
      </span>
    </span>
  );
}

/** Maps the control plane's status to a meter state. Absent means never probed. */
export function toHealthState(status?: string | null): HealthState {
  switch (status) {
    case "HEALTHY":
      return "healthy";
    case "DEGRADED":
      return "degraded";
    case "DOWN":
      return "down";
    default:
      return "unobserved";
  }
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
