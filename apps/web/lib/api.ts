import { redirect } from "next/navigation";
import { ApiError, OpsAtlasClient } from "@opsatlas/contracts";
import { currentToken } from "./session";

/**
 * The control plane, as the server sees it.
 *
 * Data is fetched in Server Components, so the API base URL never reaches the
 * browser and there is no CORS surface to configure. The one client-side write -
 * registering a manifest - goes through the route handler in app/api/register,
 * for the same reason.
 *
 * Async since authentication arrived: the caller's token lives in an httpOnly
 * cookie, and reading a cookie is async in this version of Next.
 */
export async function controlPlane(): Promise<OpsAtlasClient> {
  const token = await currentToken();
  if (!token) {
    // Nothing to try with. Sending the request anyway would trade a redirect
    // for a 401 the page would have to translate into the same redirect.
    redirect("/login");
  }
  return new OpsAtlasClient({
    baseUrl: process.env.OPSATLAS_API_URL ?? "http://localhost:8080",
    authorization: `Bearer ${token}`,
  });
}

/**
 * True when a failure means "sign in again" rather than "something is wrong".
 *
 * 401 is an expired or rejected token. 403 is a token that verified and belongs
 * to somebody this OpsAtlas does not know, and that one must NOT send the reader
 * back to a sign-in page: they would sign in successfully, land here, and be
 * turned away again - a loop that looks like the login is broken when the answer
 * is that an administrator has to provision them.
 */
export function needsSignIn(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

/**
 * Never cache catalog reads.
 *
 * A control plane that shows a stale catalog during an incident is worse than
 * one that is briefly slow. Where staleness is unavoidable the UI says so
 * explicitly rather than the cache hiding it.
 */
export const noStore = { cache: "no-store" } as const satisfies RequestInit;
