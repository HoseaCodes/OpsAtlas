/**
 * Getting a token, for the parts of a test that are not the browser.
 *
 * Several specs register a service through the API before driving the UI, and
 * since ADR 0013 that call needs a caller. It uses the same account the browser
 * signs in as, from the same issuer, because a test that set up its data as
 * somebody else would not be testing what a person can do.
 */
const ISSUER = process.env.STORM_GATE_URL ?? "http://localhost:8090";

export const OPERATOR = {
  email: process.env.OPSATLAS_E2E_EMAIL ?? "operator@opsatlas.local",
  password: process.env.OPSATLAS_E2E_PASSWORD ?? "Str0ng-Local-Passw0rd!",
};

let cached: string | undefined;

export async function operatorToken(): Promise<string> {
  if (cached) return cached;

  const response = await fetch(`${ISSUER}/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(OPERATOR),
  }).catch(() => undefined);

  if (!response) {
    throw new Error(
      `The identity provider at ${ISSUER} is not reachable. The browser suite needs the whole ` +
        `stack: make up && make first-user && make dev.`,
    );
  }
  if (!response.ok) {
    throw new Error(
      `${ISSUER} refused ${OPERATOR.email} (${response.status}). Run 'make first-user' to create it.`,
    );
  }

  const body = (await response.json()) as { accesstoken?: string };
  if (!body.accesstoken) throw new Error(`${ISSUER} signed in but returned no token.`);
  cached = body.accesstoken;
  return cached;
}

/** Headers for a control-plane call made as the operator. */
export async function asOperator(extra: Record<string, string> = {}): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await operatorToken()}`, ...extra };
}
