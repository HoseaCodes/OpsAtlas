import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const EXAMPLES = join(process.cwd(), "..", "..", "examples", "services");
const API = process.env.OPSATLAS_API_URL ?? "http://localhost:8080";

/**
 * CLAUDE.md §10 requires keyboard navigation with visible focus, and the
 * stylesheet has a `:focus-visible` rule. Nothing asserted that the rule
 * survives, which is the problem: `outline: none` somewhere in a future change
 * breaks this silently, and it is invisible to everyone who uses a mouse.
 *
 * This has to be a browser test rather than a component test. `:focus-visible`
 * only matches when the browser decides focus came from the keyboard, so a
 * jsdom test asserting the class is applied would be asserting its own
 * simulation.
 */

async function ensureRegistered(name: string) {
  const body = readFileSync(join(EXAMPLES, name), "utf8");
  const response = await fetch(`${API}/api/v1/services`, {
    method: "POST",
    headers: { "Content-Type": "application/yaml" },
    body,
  });
  if (response.ok) return;
  if (response.status !== 409) {
    throw new Error(`Setup failed to register ${name}: ${response.status} ${await response.text()}`);
  }
  const slug = name.replace(/\.ya?ml$/, "");
  const current = await fetch(`${API}/api/v1/services/${slug}`);
  const etag = current.headers.get("etag");
  if (!current.ok || !etag) throw new Error(`Setup could not read ${slug}: ${current.status}`);
  const updated = await fetch(`${API}/api/v1/services/${slug}`, {
    method: "PUT",
    headers: { "Content-Type": "application/yaml", "If-Match": etag },
    body,
  });
  if (!updated.ok) throw new Error(`Setup failed to update ${slug}: ${updated.status}`);
}

test.beforeAll(async () => {
  await ensureRegistered("orders-api.yaml");
});

/** What the browser says about wherever focus currently is. */
type Stop = {
  tag: string;
  name: string;
  href: string | null;
  focusVisible: boolean;
  outlineWidth: string;
  outlineStyle: string;
  boxShadow: string;
};

async function focusedElement(page: import("@playwright/test").Page): Promise<Stop | null> {
  return page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    if (!el || el === document.body || el === document.documentElement) return null;
    const style = getComputedStyle(el);
    return {
      tag: el.tagName.toLowerCase(),
      name: (el.getAttribute("aria-label") ?? el.textContent ?? "").trim().slice(0, 60),
      href: el instanceof HTMLAnchorElement ? new URL(el.href).pathname : null,
      focusVisible: el.matches(":focus-visible"),
      outlineWidth: style.outlineWidth,
      outlineStyle: style.outlineStyle,
      boxShadow: style.boxShadow,
    };
  });
}

/** Tab forward, collecting every stop until the walk repeats or runs out. */
async function tabThrough(page: import("@playwright/test").Page, limit = 40): Promise<Stop[]> {
  const stops: Stop[] = [];
  for (let i = 0; i < limit; i++) {
    await page.keyboard.press("Tab");
    const stop = await focusedElement(page);
    if (!stop) break;
    stops.push(stop);
  }
  return stops;
}

function hasVisibleFocus(stop: Stop): boolean {
  const outlined = stop.outlineStyle !== "none" && stop.outlineWidth !== "0px";
  const shadowed = stop.boxShadow !== "none" && stop.boxShadow !== "";
  return outlined || shadowed;
}

test("every keyboard stop on the catalog is visibly focused", async ({ page }) => {
  await page.goto("/catalog");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("services registered");

  const stops = await tabThrough(page);

  expect(stops.length, "tabbing reached nothing at all").toBeGreaterThan(3);

  // The requirement, stated as the thing that breaks: an element you can focus
  // and cannot see is the failure mode, not an element you cannot focus.
  const invisible = stops.filter((stop) => stop.focusVisible && !hasVisibleFocus(stop));
  expect(
    invisible,
    `these elements take keyboard focus without showing it: ${JSON.stringify(invisible, null, 2)}`,
  ).toEqual([]);
});

test("the catalog's own controls are reachable by keyboard, in a sensible order", async ({ page }) => {
  await page.goto("/catalog");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("services registered");

  const stops = await tabThrough(page);
  const reached = stops.map((stop) => `${stop.tag}:${stop.href ?? stop.name}`);

  // Navigation before the page's own controls, and the service list after the
  // filters that narrow it - the order someone reads the page in.
  const nav = stops.findIndex((stop) => stop.href === "/catalog");
  const search = stops.findIndex((stop) => stop.tag === "input");
  const service = stops.findIndex((stop) => stop.href === "/catalog/orders-api");

  expect(nav, `the Catalog nav link was never focused; reached ${reached.join(", ")}`).toBeGreaterThanOrEqual(0);
  expect(search, `the search box was never focused; reached ${reached.join(", ")}`).toBeGreaterThanOrEqual(0);
  expect(service, `no service link was focused; reached ${reached.join(", ")}`).toBeGreaterThanOrEqual(0);
  expect(nav, "navigation should come before the page's own controls").toBeLessThan(search);
  expect(search, "the filters should come before the list they filter").toBeLessThan(service);
});

test("a service can be opened with the keyboard alone", async ({ page }) => {
  await page.goto("/catalog");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("services registered");

  // Tab to the service link rather than clicking it, then press Enter. A link
  // that only responds to a click is not keyboard navigable however good it
  // looks.
  for (let i = 0; i < 40; i++) {
    await page.keyboard.press("Tab");
    const stop = await focusedElement(page);
    if (stop?.href === "/catalog/orders-api") break;
  }
  expect((await focusedElement(page))?.href).toBe("/catalog/orders-api");

  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/catalog\/orders-api$/);
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Orders API");
});

test("the detail tabs are reachable and activatable by keyboard", async ({ page }) => {
  await page.goto("/catalog/orders-api");
  await expect(page.getByRole("tab", { name: "Scorecard" })).toBeVisible();

  // Tabbed to, not focused programmatically. `:focus-visible` deliberately does
  // not match a scripted `.focus()`, so calling that and asserting the ring
  // appears would be testing the browser's mouse behaviour and calling it
  // keyboard support.
  let reached = false;
  for (let i = 0; i < 40; i++) {
    await page.keyboard.press("Tab");
    const stop = await focusedElement(page);
    if (stop?.name === "Scorecard") {
      reached = true;
      break;
    }
  }
  expect(reached, "the Scorecard tab was never reached by tabbing").toBe(true);

  const focused = await focusedElement(page);
  expect(focused?.focusVisible, "a tab reached by keyboard must show that it has focus").toBe(true);
  expect(hasVisibleFocus(focused as Stop)).toBe(true);

  // The tabs are links, so Enter navigates. A tab that needed a click would be
  // unreachable for anyone not using a mouse.
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/tab=scorecard/);
  await expect(page.getByRole("tab", { name: "Scorecard" })).toHaveAttribute("aria-selected", "true");
});
