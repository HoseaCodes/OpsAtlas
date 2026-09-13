import { OpsAtlasClient } from "@opsatlas/contracts";

/**
 * The control plane, as the server sees it.
 *
 * Data is fetched in Server Components, so the API base URL never reaches the
 * browser and there is no CORS surface to configure. The one client-side write -
 * registering a manifest - goes through the route handler in app/api/register,
 * for the same reason.
 */
export function controlPlane(): OpsAtlasClient {
  return new OpsAtlasClient({
    baseUrl: process.env.OPSATLAS_API_URL ?? "http://localhost:8080",
  });
}

/**
 * Never cache catalog reads.
 *
 * A control plane that shows a stale catalog during an incident is worse than
 * one that is briefly slow. Where staleness is unavoidable the UI says so
 * explicitly rather than the cache hiding it.
 */
export const noStore = { cache: "no-store" } as const satisfies RequestInit;
