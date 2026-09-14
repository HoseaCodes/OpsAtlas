import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { SESSION_COOKIE, currentToken } from "@/lib/session";
import { ThemeToggle } from "./ThemeToggle";

/**
 * The shell.
 *
 * Navigation contains Catalog and nothing else. CLAUDE.md §10: a nav item
 * appears only when its page is real. The prototype's eventual navigation -
 * Incidents, Scorecards, Golden Paths, Costs, Architecture, Audit Log - is
 * recorded in docs/roadmap.md, not rendered here as disabled links.
 */
export async function AppShell({ children }: { children: ReactNode }) {
  const signedIn = (await currentToken()) !== undefined;

  async function signOut() {
    "use server";
    // Deleting the cookie ends the session here. The token itself stays valid
    // at the issuer until it expires - a resource server cannot revoke what it
    // did not mint, and pretending otherwise would be the more dangerous lie.
    (await cookies()).delete(SESSION_COOKIE);
    redirect("/login");
  }

  return (
    <div className="grid min-h-full grid-cols-1 md:grid-cols-[216px_minmax(0,1fr)]">
      <aside
        className="flex flex-row items-center gap-4 overflow-x-auto border-b border-rule px-4 py-3
                   md:sticky md:top-0 md:h-screen md:flex-col md:items-stretch md:gap-5
                   md:border-b-0 md:border-r md:px-3.5 md:py-5"
      >
        <div className="flex flex-none flex-col gap-0.5 px-1.5">
          <b className="text-[15px] font-semibold tracking-[-0.01em]">OpsAtlas</b>
          <span className="mono text-[11.5px] text-ink-3">control-plane / local</span>
        </div>

        <nav aria-label="Sections" className="flex flex-row gap-4 md:flex-col md:gap-3.5">
          <div className="flex flex-row gap-0.5 md:flex-col">
            <em className="hidden border-b border-rule px-2 pb-1.5 text-[11.5px] not-italic text-ink-3 md:mb-1 md:block">
              Operate
            </em>
            <Link
              href="/catalog"
              className="rounded-[5px] px-2 py-1.5 text-ink-2 hover:bg-surface-2 hover:text-ink"
            >
              Catalog
            </Link>
            <Link
              href="/sources"
              className="rounded-[5px] px-2 py-1.5 text-ink-2 hover:bg-surface-2 hover:text-ink"
            >
              Sources
            </Link>
          </div>
        </nav>

        <div className="ml-auto flex flex-none items-center gap-2 md:ml-0 md:mt-auto md:border-t md:border-rule md:pt-3.5">
          <ThemeToggle />
          {signedIn ? (
            // No name beside it: the token carries a subject and an expiry and
            // nothing a person would recognise, and inventing a display name
            // would be a UI element implying something the backend does not
            // have (CLAUDE.md section 10).
            <form action={signOut}>
              <button
                type="submit"
                className="rounded-[5px] px-2 py-1.5 text-[13px] text-ink-2 hover:bg-surface-2 hover:text-ink"
              >
                Sign out
              </button>
            </form>
          ) : null}
        </div>
      </aside>

      <main className="flex min-w-0 flex-col">{children}</main>
    </div>
  );
}
