"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { Violation } from "@opsatlas/contracts";

/**
 * Editing and removing one service.
 *
 * Both carry `If-Match` built from the version this page was rendered with, so
 * a change made by somebody else between the read and the write is refused by
 * the control plane rather than overwritten. The console does not retry on 412:
 * the whole point is that the reader sees the newer state before deciding again.
 *
 * Deletion asks for the slug to be typed. Not theatre — it is the one action in
 * this console that cannot be undone from inside the system, and a misplaced
 * click on a row you did not mean is otherwise all it takes.
 */

type State =
  | { kind: "idle" }
  | { kind: "working" }
  | { kind: "failed"; title: string; detail: string; correlationId: string; violations: Violation[] };

function readProblem(body: Record<string, unknown>, fallback: string): State {
  return {
    kind: "failed",
    title: typeof body.title === "string" ? body.title : fallback,
    detail: typeof body.detail === "string" ? body.detail : "The request failed.",
    correlationId: typeof body.correlationId === "string" ? body.correlationId : "unknown",
    violations: Array.isArray(body.violations) ? (body.violations as Violation[]) : [],
  };
}

export function ServiceAdmin({
  slug,
  version,
  manifest,
  lifecycle,
}: {
  slug: string;
  version: number;
  manifest: unknown;
  lifecycle: string;
}) {
  const router = useRouter();
  const [open, setOpen] = useState<"none" | "edit" | "delete">("none");
  const [document, setDocument] = useState(() => JSON.stringify(manifest, null, 2));
  const [confirmation, setConfirmation] = useState("");
  const [state, setState] = useState<State>({ kind: "idle" });

  const busy = state.kind === "working";

  async function save(event: React.FormEvent) {
    event.preventDefault();
    setState({ kind: "working" });

    const response = await fetch(`/api/services/${encodeURIComponent(slug)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/yaml", "If-Match": `"${version}"` },
      body: document,
    });

    if (response.ok) {
      setState({ kind: "idle" });
      setOpen("none");
      router.refresh();
      return;
    }
    setState(readProblem((await response.json()) as Record<string, unknown>, "The manifest was not accepted"));
  }

  async function remove(event: React.FormEvent) {
    event.preventDefault();
    setState({ kind: "working" });

    const response = await fetch(`/api/services/${encodeURIComponent(slug)}`, {
      method: "DELETE",
      headers: { "If-Match": `"${version}"` },
    });

    if (response.status === 204) {
      // Nothing left to render here, so leave rather than showing a page about
      // a service that no longer exists.
      router.push("/catalog");
      router.refresh();
      return;
    }
    setState(readProblem((await response.json()) as Record<string, unknown>, "The service was not deleted"));
  }

  return (
    <section>
      <h2 className="mb-2.5 text-[13px] font-semibold">Manage</h2>

      <p className="mb-3 max-w-prose text-[12.5px] text-ink-3">
        This entry is at version <span className="mono">{version}</span> and its lifecycle is{" "}
        <span className="mono">{lifecycle}</span>. Both actions below send that version, so either is
        refused if somebody has changed the service since this page loaded.
      </p>

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => {
            setOpen(open === "edit" ? "none" : "edit");
            setState({ kind: "idle" });
          }}
          aria-expanded={open === "edit"}
          className="cursor-pointer rounded-[5px] border border-rule-2 bg-transparent px-2.5 py-1.5 text-[12.5px] text-ink-2 hover:border-ink-3 hover:text-ink"
        >
          Edit manifest
        </button>
        <button
          type="button"
          onClick={() => {
            setOpen(open === "delete" ? "none" : "delete");
            setConfirmation("");
            setState({ kind: "idle" });
          }}
          aria-expanded={open === "delete"}
          className="cursor-pointer rounded-[5px] border border-rule-2 bg-transparent px-2.5 py-1.5 text-[12.5px] hover:border-ink-3"
          style={{ color: "var(--alarm)" }}
        >
          Delete
        </button>
      </div>

      {open === "edit" ? (
        <form onSubmit={save} className="mt-3.5 flex flex-col gap-2.5">
          <label htmlFor="edit-manifest" className="text-[12.5px] text-ink-2">
            Replacement manifest — YAML or JSON
          </label>
          <textarea
            id="edit-manifest"
            value={document}
            onChange={(event) => setDocument(event.target.value)}
            spellCheck={false}
            rows={18}
            className="mono w-full resize-y rounded-[6px] border border-rule-2 bg-transparent p-2.5 text-[12.5px]"
          />
          <p className="max-w-prose text-[12.5px] text-ink-3">
            Pre-filled with the stored manifest, which is held as JSON. Replacing it re-runs
            validation and re-scores the service, and is recorded in the audit log as an update.
            To retire a service instead of deleting it, set{" "}
            <span className="mono">spec.lifecycle</span> to{" "}
            <span className="mono">retired</span> here — the entry and its history stay.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="submit"
              disabled={busy}
              className="cursor-pointer rounded-[5px] border border-transparent px-2.5 py-1.5 text-[12.5px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-60"
              style={{ background: "var(--accent)" }}
            >
              {busy ? "Saving…" : "Save manifest"}
            </button>
            <button
              type="button"
              onClick={() => setOpen("none")}
              className="cursor-pointer rounded-[5px] border border-rule-2 bg-transparent px-2.5 py-1.5 text-[12.5px] text-ink-2 hover:border-ink-3 hover:text-ink"
            >
              Cancel
            </button>
          </div>
        </form>
      ) : null}

      {open === "delete" ? (
        <form onSubmit={remove} className="mt-3.5 flex flex-col gap-2.5">
          <p className="max-w-prose text-[12.5px]" style={{ color: "var(--alarm)" }}>
            Deleting removes this service, its environments, its scorecard history and its probe
            history. It cannot be undone from here.
          </p>
          <p className="max-w-prose text-[12.5px] text-ink-3">
            The audit log survives: what was registered, by whom, and that it was deleted stays
            answerable afterwards. If this manifest comes from a watched repository, stop watching
            the source first — otherwise the next sync registers it again. Retiring it instead, via{" "}
            <span className="mono">spec.lifecycle: retired</span>, keeps the entry and everything
            recorded against it.
          </p>
          <label htmlFor="confirm-slug" className="text-[12.5px] text-ink-2">
            Type <span className="mono">{slug}</span> to confirm
          </label>
          <input
            id="confirm-slug"
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
            autoComplete="off"
            spellCheck={false}
            className="mono w-full max-w-[36ch] rounded-[6px] border border-rule-2 bg-transparent px-2.5 py-1.5 text-[12.5px]"
          />
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="submit"
              disabled={busy || confirmation !== slug}
              className="cursor-pointer rounded-[5px] border px-2.5 py-1.5 text-[12.5px] font-medium disabled:cursor-not-allowed disabled:opacity-50"
              style={{ borderColor: "var(--alarm)", color: "var(--alarm)" }}
            >
              {busy ? "Deleting…" : "Delete this service"}
            </button>
            <button
              type="button"
              onClick={() => setOpen("none")}
              className="cursor-pointer rounded-[5px] border border-rule-2 bg-transparent px-2.5 py-1.5 text-[12.5px] text-ink-2 hover:border-ink-3 hover:text-ink"
            >
              Cancel
            </button>
          </div>
        </form>
      ) : null}

      {state.kind === "failed" ? (
        <div role="alert" className="mt-3.5 rounded-[6px] border border-rule-2 p-3">
          <b className="text-[13px] font-semibold" style={{ color: "var(--alarm)" }}>
            {state.title}
          </b>
          <p className="mt-1 max-w-prose text-[12.5px] text-ink-2">{state.detail}</p>
          {state.violations.length > 0 ? (
            <ul className="mt-2 flex flex-col gap-1">
              {state.violations.map((violation, index) => (
                <li key={`${violation.pointer}-${index}`} className="text-[12.5px]">
                  <span className="mono text-ink-3">{violation.pointer}</span> {violation.message}
                  {violation.expected ? (
                    <span className="mt-0.5 block text-[12px] text-ink-3">
                      Expected: {violation.expected}
                    </span>
                  ) : null}
                </li>
              ))}
            </ul>
          ) : null}
          <p className="mono mt-2 text-[11.5px] text-ink-3">{state.correlationId}</p>
        </div>
      ) : null}
    </section>
  );
}
