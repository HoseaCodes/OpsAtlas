import { RegisterForm } from "@/components/RegisterForm";

export const metadata = { title: "Register a service — OpsAtlas" };

export default function RegisterPage() {
  return (
    <>
      <header className="border-b border-rule px-4 pb-5 pt-6 md:px-6">
        <h1 className="text-[19px] font-semibold tracking-[-0.02em]">Register a service</h1>
        <p className="mt-1.5 max-w-[68ch] text-[13.5px] text-ink-2">
          Paste the <span className="mono">service.yaml</span> from the service&apos;s repository. It is
          validated against the versioned schema, and anything wrong with it is reported by the exact
          location in the document rather than as &ldquo;invalid&rdquo;.
        </p>
        <p className="mt-2 max-w-[68ch] text-[12.5px] text-ink-3">
          Submission is by paste in this phase. Reading manifests directly from GitHub needs an App
          identity and a permission model, and is a later phase — so the repository recorded against a
          service is whatever its manifest declares.
        </p>
      </header>

      <RegisterForm />
    </>
  );
}
