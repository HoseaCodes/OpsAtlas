import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SignInFailed, sessionCookie, signIn } from "@/lib/session";

/**
 * Signing in.
 *
 * Deliberately the only page that works without a session, and deliberately
 * plain: it collects two fields and hands them to the identity provider. The
 * console never sees a password again after this request, and never stores one.
 */
export const dynamic = "force-dynamic";

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string }>;
}) {
  const { error } = await searchParams;

  async function submit(form: FormData) {
    "use server";

    const email = String(form.get("email") ?? "");
    const password = String(form.get("password") ?? "");

    let token: string;
    try {
      token = await signIn(email, password);
    } catch (failure) {
      if (failure instanceof SignInFailed) {
        // Back to the form with a message, and without the password in a URL.
        redirect("/login?error=rejected");
      }
      // The provider being unreachable is a different fact from the credentials
      // being wrong, and saying "wrong password" when the truth is "the identity
      // provider is down" sends the reader to change a password that was fine.
      redirect("/login?error=unreachable");
    }

    (await cookies()).set(sessionCookie(token));
    redirect("/catalog");
  }

  return (
    <section aria-labelledby="sign-in-heading" className="mx-auto w-full max-w-sm px-4 py-16">
      <h1 id="sign-in-heading" className="text-[19px] font-semibold text-ink">
        Sign in
      </h1>
      <p className="mt-2 text-[13px] text-ink-2">
        OpsAtlas verifies your identity with the configured provider. It never stores your password.
      </p>

      {error ? (
        <p
          role="alert"
          className="mt-4 rounded-md border border-rule bg-surface px-3 py-2 text-[13px] text-ink"
        >
          {error === "unreachable"
            ? "The identity provider could not be reached. This is not a problem with your password."
            : "Those credentials were not accepted."}
        </p>
      ) : null}

      <form action={submit} className="mt-6 flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <label htmlFor="email" className="text-[13px] font-medium text-ink">
            Email
          </label>
          <input
            id="email"
            name="email"
            type="email"
            required
            autoComplete="username"
            className="w-full rounded-md border border-rule bg-surface px-2.5 py-1.5 text-[14px] text-ink"
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="password" className="text-[13px] font-medium text-ink">
            Password
          </label>
          <input
            id="password"
            name="password"
            type="password"
            required
            autoComplete="current-password"
            className="w-full rounded-md border border-rule bg-surface px-2.5 py-1.5 text-[14px] text-ink"
          />
        </div>

        <button
          type="submit"
          className="rounded-md border border-rule px-3 py-1.5 text-[13px] font-medium text-ink"
          style={{ borderColor: "var(--accent)" }}
        >
          Sign in
        </button>
      </form>
    </section>
  );
}
