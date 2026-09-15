import type { PolicyRules } from "@opsatlas/contracts";
import schema from "@opsatlas/contracts/schema";
import { ManifestPrompt } from "@/components/ManifestPrompt";
import { RegisterForm } from "@/components/RegisterForm";
import { controlPlane, noStore } from "@/lib/api";
import { buildManifestPrompt, type JsonSchemaNode } from "@/lib/manifestPrompt";

export const metadata = { title: "Register a service — OpsAtlas" };

// The prompt embeds the policy rules as the control plane currently states them,
// so it is built per request rather than at build time.
export const dynamic = "force-dynamic";

export default async function RegisterPage() {
  // The prompt is worth having without the rules, so a failure here degrades it
  // rather than failing the page: registering a manifest does not depend on the
  // rule list, and that is the partial-failure case CLAUDE.md §10 calls normal.
  const rules = await (await controlPlane())
    .getPolicyRules(noStore)
    .catch((): null => null);

  const prompt = buildManifestPrompt(schema as JsonSchemaNode, rules as PolicyRules | null);

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

      <div className="px-4 pt-5 md:px-6">
        <ManifestPrompt prompt={prompt} />
      </div>

      <RegisterForm />
    </>
  );
}
