import { ApiError } from "@opsatlas/contracts";
import type { components } from "@opsatlas/contracts";
import { SourceList } from "@/components/SourceList";
import { WatchForm } from "@/components/WatchForm";
import { Empty, ErrorState } from "@/components/states";
import { controlPlane, noStore } from "@/lib/api";

export const dynamic = "force-dynamic";
export const metadata = { title: "Sources — OpsAtlas" };

type SourceView = components["schemas"]["SourceView"];

export default async function SourcesPage() {
  let sources: SourceView[];
  try {
    const response = await controlPlane().listSources({ limit: 100 }, noStore);
    sources = response.items;
  } catch (error) {
    const problem = error instanceof ApiError ? error : null;
    return (
      <ErrorState
        title="Sources could not be loaded"
        detail={problem?.detail ?? "The control plane did not respond. Check that it is running: make dev."}
        correlationId={problem?.correlationId}
      />
    );
  }

  const failing = sources.filter((source) => source.lastOutcome !== null && !healthy(source.lastOutcome)).length;

  return (
    <>
      <header className="border-b border-rule px-4 pb-5 pt-6 md:px-6">
        <h1 className="text-[19px] font-semibold tracking-[-0.02em]">
          <span className="mono font-medium tracking-[-0.03em]">{sources.length}</span>
          {sources.length === 1 ? " repository watched" : " repositories watched"}
          {failing > 0 ? (
            <>
              , <span className="mono font-medium tracking-[-0.03em]">{failing}</span> failing.
            </>
          ) : (
            "."
          )}
        </h1>
        <p className="mt-2 max-w-[72ch] text-[13.5px] text-ink-2">
          OpsAtlas reads <span className="mono">service.yaml</span> from each of these on a schedule and registers
          what it finds. It <b className="font-medium text-ink">only ever reads</b> — no webhook is registered and
          no write access is held or needed.
        </p>
        <p className="mt-2 max-w-[72ch] text-[12.5px] text-ink-3">
          A failing sync never removes what is already registered. The catalog goes stale rather than blank, and the
          status below says which.
        </p>
      </header>

      <WatchForm />

      {sources.length === 0 ? (
        <Empty title="No repositories are watched yet.">
          Watch one above and OpsAtlas will read its manifest, or paste a manifest directly on the register page. A
          public repository needs no credentials.
        </Empty>
      ) : (
        <SourceList sources={sources} />
      )}
    </>
  );
}

function healthy(outcome: string | null): boolean {
  return outcome === "REGISTERED" || outcome === "UPDATED" || outcome === "UNCHANGED";
}
