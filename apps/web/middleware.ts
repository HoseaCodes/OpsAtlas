import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/session";

/**
 * Sends a reader without a session to the sign-in page.
 *
 * Done here rather than in each page, for a reason found the hard way: pages
 * wrap their control-plane call in try/catch to render a real error state, and
 * Next's `redirect()` works by throwing - so a redirect raised inside that call
 * is caught by the page's own error handling and rendered as "the catalog could
 * not be loaded". The reader is told something is broken when the truth is that
 * they are not signed in.
 *
 * Middleware runs before any of that and cannot be swallowed. It also covers
 * pages that do not exist yet, which per-page checks would not.
 */
export function middleware(request: NextRequest) {
  if (request.cookies.get(SESSION_COOKIE)) {
    return NextResponse.next();
  }

  const signIn = new URL("/login", request.url);
  return NextResponse.redirect(signIn);
}

export const config = {
  /**
   * Everything except the sign-in page itself, the route handlers, and static
   * assets.
   *
   * The route handlers are excluded deliberately: they answer a fetch, not a
   * navigation, and a 302 to an HTML page would arrive at code expecting a
   * problem document. They check the session themselves and answer 401.
   */
  matcher: ["/((?!login|api|_next/static|_next/image|favicon.ico).*)"],
};
