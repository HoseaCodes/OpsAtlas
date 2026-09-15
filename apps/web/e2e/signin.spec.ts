import { expect, test } from "@playwright/test";

/**
 * Signing in, against a real identity provider.
 *
 * This suite needs Storm-Gate running and reachable at STORM_GATE_URL, with the
 * account below provisioned in OpsAtlas. That is a real dependency rather than a
 * stub on purpose: the thing worth testing is that a token this console obtained
 * from an issuer is accepted by the control plane, and a fake issuer would prove
 * only that the fake agrees with itself.
 */

test("a person signs in and reads the catalog", async ({ page }) => {
  await page.goto("/catalog");
  await expect(page).toHaveURL(/\/login/);
  console.log("unauthenticated visit redirected to:", page.url());

  await page.getByLabel("Email").fill("operator@opsatlas.local");
  await page.getByLabel("Password").fill("Str0ng-Local-Passw0rd!");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/catalog/, { timeout: 15_000 });
  console.log("after sign-in:", page.url());
  await expect(page.getByRole("heading", { level: 1 })).toContainText("services registered");
  console.log("catalog heading:", await page.getByRole("heading", { level: 1 }).textContent());

  // The session survives a reload, and sign-out ends it.
  await page.reload();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("services registered");

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login/);
  console.log("after sign-out:", page.url());
});

test("wrong credentials say so, without saying which half was wrong", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill("operator@opsatlas.local");
  await page.getByLabel("Password").fill("not-the-password");
  await page.getByRole("button", { name: "Sign in" }).click();

  // Scoped to the page's own region: Next injects a role="alert" of its own for
  // route announcements, so an unscoped query matches two things.
  const message = page.getByRole("region", { name: "Sign in" }).getByRole("alert");
  await expect(message).toContainText("not accepted");
  console.log("rejected message:", await message.textContent());
});
