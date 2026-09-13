/**
 * Labels data the platform did not measure.
 *
 * CLAUDE.md §3 rule 2 and §10: mocked or absent data is marked in the interface,
 * not only in a comment. Nothing in slice one is mocked - so what this actually
 * labels is the gap where a measurement will eventually go.
 */
export function NotObservedBadge() {
  return (
    <span
      className="inline-flex items-center rounded-[4px] border border-dashed border-rule-2 px-1.5 py-0.5 text-[11px] text-ink-3"
      title="Health monitoring arrives with the observer. Nothing in this system probes anything yet."
    >
      never observed
    </span>
  );
}
