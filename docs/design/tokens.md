# Design tokens and rules

Extracted once from `service-operations-platform-v3.html` (the v3 prototype, 1,665
lines). **This note replaces that file.** Work from here; do not re-read the
prototype, and do not port its markup or its seeded data.

The prototype itself is not committed — see
[ADR 0006](../adr/0006-prototype-is-not-committed.md) for why.

---

## 1. The rule everything else serves

**Status is carried by shape, height and weight. Never by hue.**

The test, from `CLAUDE.md` §10: screenshot any page, convert to greyscale, and
every status must still be readable. Colour may reinforce a signal; it may never
be the only thing carrying one.

Colour is rationed to exactly four jobs:

| Job | Token |
|---|---|
| Selection (the chosen row, the active tab) | `--accent` |
| Focus ring | `--accent` |
| Error-budget fill | `--accent` |
| Open-incident emphasis | `--alarm` |

Nothing else gets colour. A failing scorecard check is **not** red — it is a
filled square where a passing one is a thin check.

---

## 2. Colour tokens

Light is the base; dark redefines the same names. Both are complete.

```css
:root {                                    /* light */
  --bg:#ECEEEB;  --surface:#FAFBF9;  --surface-2:#E3E7E2;
  --ink:#1A1F1C; --ink-2:#5C655F;   --ink-3:#8A938C;
  --rule:#D2D7D1; --rule-2:#BFC6BF;
  --accent:#24479B; --alarm:#8A2B22;
  --shadow:0 12px 28px rgba(26,31,28,.10);
}
:root[data-theme="dark"] {
  --bg:#131715;  --surface:#1B201D;  --surface-2:#232A26;
  --ink:#E7EAE5; --ink-2:#9BA49C;   --ink-3:#6F7872;
  --rule:#2C332E; --rule-2:#3A423C;
  --accent:#8AA6EE; --alarm:#DB9186;
  --shadow:0 12px 28px rgba(0,0,0,.45);
}
```

**Decision for the rebuild: dark is the default.** `CLAUDE.md` §10 says the dark
theme is carried over; the prototype shipped light-first with a toggle. The
console defaults to dark and keeps the toggle. The full light palette above stays
defined and supported — the greyscale rule means neither theme is load-bearing
for meaning.

Scale roles: `--ink` primary text and status marks · `--ink-2` secondary text and
labels · `--ink-3` tertiary, units, timestamps · `--rule` hairlines between rows ·
`--rule-2` borders on interactive chrome.

---

## 3. Type

```css
--sans: 'Archivo', 'Helvetica Neue', Arial, sans-serif;
--mono: 'IBM Plex Mono', 'SFMono-Regular', Menlo, monospace;
```

- Body: `400 14px/1.5` sans.
- **Every number is mono**, with `font-feature-settings: "zero" 1` so a zero
  cannot be read as an O. Counts, currency, latency, SHAs, timestamps, versions.
- Headings: 600 weight, `letter-spacing: -.02em`, never larger than needed.
  View title 19px; page hero `clamp(21px, 2.6vw, 31px)`.
- Small print: 12.5px for labels, 11.5px for column headers and tertiary metadata.
- Measure is capped: hero `max-width: 22ch`, lede `68ch`, notes `64ch`.

Both faces load from Google Fonts in the prototype. The rebuild self-hosts them
via `next/font` so the console has no third-party request on the critical path.

---

## 4. Status encodings — the ones that must survive

### 4.1 Health meter — three notches, height encodes state

```
healthy    degraded    down
  _         _            _
 | |       | |          | |
_| |      _| |         _| |
|||       ||_|         |||__      (bar heights, px)
6 10 13   6 10 4       6 3 3
```

3px-wide bars, 2px gap, 13px tall box, `background: var(--ink)`, `border-radius: 1px`.
Rising = healthy, falling off at the end = degraded, collapsed = down. It is
legible at 13px and in greyscale, which a dot is not.

Always paired with a text label (`Healthy` / `Degraded` / `Down`) for screen
readers; the meter itself is `aria-hidden`.

### 4.2 Thirty-day ribbon — one bar per day, height encodes SLO attainment

4px wide, 1.5px gap, 16px tall. `ok` = 16px at .78 opacity · `warn` = 9px at .5 ·
`bad` = 4px at .32. A compact `.sm` variant (3px wide, 12/7/3px) sits in table rows.

### 4.3 Scorecard result — shape, not colour

- **Pass**: a thin stroked check path, 13×13, `stroke-width: 1.5`.
- **Fail**: a *filled* rounded square with a knocked-out exclamation.
- **Not applicable**: a short horizontal rule at 55% opacity.

The weight difference (stroke vs fill) is what carries the signal. Failing rows
additionally get `font-weight: 500` and `--alarm` on the label text — reinforcement,
never the sole carrier.

### 4.4 Severity — a count of filled marks

Three 6×6 squares. SEV1 = 3 filled, SEV2 = 2, SEV3 = 1. Unfilled marks are an
inset 1px outline. Open incidents fill with `--alarm`; resolved ones fill with
`--ink`.

---

## 5. Layout

| Thing | Value |
|---|---|
| Shell | `grid-template-columns: 216px minmax(0,1fr)` — sticky rail + main |
| View padding | 24px, dropping to 16px below 820px |
| Row padding | 13px vertical, 24px horizontal |
| Card | `--surface`, 1px `--rule`, 8px radius, 14px padding |
| Radius scale | 5px controls · 6px inputs and buttons · 8px cards and panels |
| Focus | `outline: 2px solid var(--accent); outline-offset: 2px` on `:focus-visible` |

Breakpoints, in the prototype's order:

- **1180px** — the four-answer strip goes 4-up to 2-up.
- **1080px** — the catalog split collapses; list and detail become one column with
  a back button.
- **820px** — the rail goes horizontal and scrollable; nav group labels and rail
  counters hide.
- **560px** — table columns drop to name + two; definition lists stack.

`@media (prefers-reduced-motion: reduce)` zeroes every transition and animation.
Carry this over verbatim.

---

## 6. Information architecture

The prototype's full navigation, recorded here as the eventual target:

```
Operate    Catalog · Incidents · Cost
Govern     Scorecards · Teams · Activity
Build      Golden paths · Architecture
```

**`CLAUDE.md` §10: a nav item appears only when its page is real.** Slice one
ships **Catalog and nothing else.** No disabled items, no "coming soon".

### Catalog view, as prototyped

1. Hero: `N services registered. N degraded, N outside policy.` with a last-sync line.
2. A four-answer strip — *What is broken? · Who owns it? · What depends on it? ·
   What is it costing us?* — each one a jump into the view that answers it.
3. Split pane. List columns: Service and owner · Health · 30 days · Cost/mo · Policy.
   Detail tabs: Overview · Blast radius · Observability · Cost · Pipeline ·
   Scorecard · service.yaml.

**Slice one builds a strict subset:** the hero without the degraded/incident
counts, no four-answer strip (three of its four questions need data we do not
have), the list without Cost, and detail tabs Overview · Scorecard · service.yaml.
See `docs/roadmap.md` for when the rest becomes real.

---

## 7. Required states

Every list and panel implements all five. The prototype only had three; the
other two are `CLAUDE.md` §10 requirements.

| State | What it must say |
|---|---|
| Loading | Skeleton at the real row height, so nothing reflows on arrival. |
| Empty | Why it is empty and the single next action. Never just "No results". |
| **Stale** | The data shown, plus when it was last known good. |
| **Partial failure** | Which parts loaded and which did not, with the rest still usable. A control plane whose upstreams are down is the normal case. |
| Error | What failed, the correlation ID, and what to do. |

**Slice one adds a sixth: `Never observed.`** Health and the 30-day ribbon are
observer-fed, and the observer is a later phase. These columns render an explicit
never-observed state — not a fake meter, and not a hidden column.

---

## 8. Prohibited

From `CLAUDE.md` §10, and non-negotiable:

- No emojis.
- No buttons that do nothing.
- No UI element implying a capability the backend does not have.
- Mocked data is labelled **in the interface**, not only in a comment.

## 9. Example data

Example service names are neutral: `orders-api`, `pricing-engine`,
`billing-worker`, `customer-portal`, `identity-bff`. Never insurance-specific,
never anything that reads as derived from an employer's systems.

The v3 prototype's seeded data violates this rule throughout — it is the reason
that file is not committed. The fixtures in `examples/services/` are the
replacement and the only example data in this repository.
