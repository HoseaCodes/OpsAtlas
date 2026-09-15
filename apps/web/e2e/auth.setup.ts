import { expect, test as setup } from "@playwright/test";
import { OPERATOR } from "./support/session";

/**
 * Signs in once, so every other spec starts as somebody.
 *
 * Done through the form rather than by writing the cookie directly: the cookie's
 * name, flags and lifetime are the console's business, and a test that forged
 * one would keep passing after the console stopped issuing it.
 *
 * The saved state is reused by every other spec. `signin.spec.ts` is the
 * exception - it asserts what happens *before* there is a session, so it clears
 * it and signs in itself.
 */
const SESSION = "e2e/.auth/session.json";

setup("sign in", async ({ page }) => {
  await page.goto("/login");

  await page.getByLabel("Email").fill(OPERATOR.email);
  await page.getByLabel("Password").fill(OPERATOR.password);
  await page.getByRole("button", { name: "Sign in" }).click();

  // Landing on the catalog is the proof. A 403 here means the account exists at
  // the issuer but is not provisioned in OpsAtlas, which `make first-user`
  // followed by a control-plane restart fixes.
  await expect(page).toHaveURL(/\/catalog/, { timeout: 15_000 });
  await expect(page.getByRole("heading", { level: 1 })).toContainText("services registered");

  await page.context().storageState({ path: SESSION });
});
