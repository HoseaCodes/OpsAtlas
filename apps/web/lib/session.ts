import { cookies } from "next/headers";

/**
 * Who is signed in, from the browser's point of view.
 *
 * The console holds the caller's access token in an httpOnly cookie and sends
 * it to the control plane on their behalf. It deliberately does not hold a
 * credential of its own: a console with its own service key would make every
 * action in the audit log read as "the console did it", which is the fact
 * authentication was added to stop being true (ADR 0013).
 */

/** httpOnly so page scripts cannot read it, and no XSS can exfiltrate it. */
export const SESSION_COOKIE = "opsatlas_session";

export async function currentToken(): Promise<string | undefined> {
  const store = await cookies();
  return store.get(SESSION_COOKIE)?.value;
}

/**
 * Exchanges an email and password for an access token at the identity provider.
 *
 * The password reaches this process and no further: it is posted to the issuer
 * from the server and never stored, logged, or put in the cookie. What comes
 * back is a token that expires.
 */
export async function signIn(email: string, password: string): Promise<string> {
  const issuer = process.env.STORM_GATE_URL ?? "http://localhost:8090";

  const response = await fetch(`${issuer}/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
    cache: "no-store",
  });

  if (!response.ok) {
    // Deliberately the same message whether the account is unknown or the
    // password is wrong. Telling them apart is a way to enumerate accounts.
    throw new SignInFailed("Those credentials were not accepted.");
  }

  const body = (await response.json()) as { accesstoken?: string };
  if (!body.accesstoken) {
    throw new SignInFailed("The identity provider accepted the sign-in but returned no token.");
  }
  return body.accesstoken;
}

export class SignInFailed extends Error {}

/**
 * Cookie settings, in one place.
 *
 * `sameSite: "lax"` is doing real work rather than being a default: the session
 * is a cookie, so a cross-site form could otherwise POST to this console's route
 * handlers and the browser would attach it. Lax withholds the cookie on
 * cross-site POSTs, which is the shape that attack has to take.
 */
export function sessionCookie(token: string) {
  return {
    name: SESSION_COOKIE,
    value: token,
    httpOnly: true,
    sameSite: "lax" as const,
    secure: process.env.NODE_ENV === "production",
    path: "/",
    // Shorter than the token's own lifetime would be a lie about when it stops
    // working; longer would leave a dead cookie looking like a session.
    maxAge: 60 * 60 * 24,
  };
}
