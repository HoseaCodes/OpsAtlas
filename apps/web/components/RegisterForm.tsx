"use client";

import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import type { ServiceDetail, Violation } from "@opsatlas/contracts";

type Result =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "registered"; service: ServiceDetail; created: boolean }
  | { kind: "rejected"; title: string; detail: string; correlationId: string; violations: Violation[] };

const EXAMPLE = `apiVersion: opsatlas.ambitiousconcepts.io/v1
kind: Service
metadata:
  name: orders-api
  displayName: Orders API
  owner: ambitious-concepts
  repository: ambitious-concepts/orders-api
spec:
  tier: 1
  runtime: spring-boot
  environments:
    - name: production
      url: https://orders.example.com
  health:
    readiness: /actuator/health/readiness
    liveness: /actuator/health/liveness
  observability:
    serviceName: orders-api
  operations:
    slo:
      availability: 99.9
      window: 30d
    runbook: docs/runbook.md
  journeys:
    - Place an order
  dependencies:
    - name: postgres
      kind: datastore
`;

export function RegisterForm() {
  const router = useRouter();
  const [document, setDocument] = useState("");
  const [sourcePath, setSourcePath] = useState("service.yaml");
  const [result, setResult] = useState<Result>({ kind: "idle" });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setResult({ kind: "submitting" });

    const response = await fetch(`/api/register?sourcePath=${encodeURIComponent(sourcePath)}`, {
      method: "POST",
      headers: { "Content-Type": "application/yaml" },
      body: document,
    });

    const body = (await response.json()) as Record<string, unknown>;

    if (response.ok) {
      // 201 created, 200 a replay of a manifest already registered unchanged.
      // Those are different events and the form says which one happened.
      setResult({
        kind: "registered",
        service: body as unknown as ServiceDetail,
        created: response.status === 201,
      });
      router.refresh();
      return;
    }

    setResult({
      kind: "rejected",
      title: typeof body.title === "string" ? body.title : "The manifest was not accepted",
      detail: typeof body.detail === "string" ? body.detail : "The request failed.",
      correlationId: typeof body.correlationId === "string" ? body.correlationId : "unknown",
      violations: Array.isArray(body.violations) ? (body.violations as Violation[]) : [],
    });
  }

  return (
    <div className="flex flex-col gap-5 px-4 py-5 md:px-6">
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <label htmlFor="manifest" className="text-[12.5px] text-ink-2">
              service.yaml
            </label>
            <button
              type="button"
              onClick={() => setDocument(EXAMPLE)}
              className="cursor-pointer rounded-[5px] border border-rule-2 bg-transparent px-2 py-1 text-[12px] text-ink-2 hover:border-ink-3 hover:text-ink"
            >
              Fill in an example
            </button>
          </div>
          <textarea
            id="manifest"
            value={document}
            onChange={(event) => setDocument(event.target.value)}
            spellCheck={false}
            rows={18}
            placeholder="Paste the contents of service.yaml"
            className="mono w-full resize-y rounded-md border border-rule bg-surface p-3 text-[12.5px] leading-[1.65] text-ink placeholder:text-ink-3"
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="source-path" className="text-[12.5px] text-ink-2">
            Path in the repository
          </label>
          <input
            id="source-path"
            value={sourcePath}
            onChange={(event) => setSourcePath(event.target.value)}
            spellCheck={false}
            className="mono max-w-[340px] rounded-md border border-rule bg-surface px-2.5 py-1.5 text-[12.5px] text-ink"
          />
          <span className="text-[12px] text-ink-3">
            Together with the repository named inside the manifest, this is what identifies the service for
            re-registration.
          </span>
        </div>

        <div className="flex items-center gap-3">
          <button
            type="submit"
            disabled={result.kind === "submitting" || document.trim().length === 0}
            className="cursor-pointer rounded-md px-3.5 py-1.5 text-[14px] font-medium disabled:cursor-not-allowed disabled:opacity-50"
            style={{ background: "var(--accent)", color: "var(--bg)" }}
          >
            {result.kind === "submitting" ? "Validating…" : "Register service"}
          </button>
          <span className="text-[12.5px] text-ink-3">
            Nothing is stored unless the manifest validates.
          </span>
        </div>
      </form>

      {result.kind === "rejected" ? <Rejected result={result} /> : null}
      {result.kind === "registered" ? <Registered result={result} /> : null}
    </div>
  );
}

function Rejected({ result }: { result: Extract<Result, { kind: "rejected" }> }) {
  return (
    <div
      role="alert"
      className="card max-w-[80ch] border-l-[3px] p-4"
      style={{ borderLeftColor: "var(--alarm)" }}
    >
      <b className="block font-medium" style={{ color: "var(--alarm)" }}>
        {result.title}
      </b>
      <p className="mt-1.5 text-[13px] text-ink-2">{result.detail}</p>

      {result.violations.length > 0 ? (
        <table className="plain mt-3.5">
          <thead>
            <tr>
              <th>Where</th>
              <th>What is wrong</th>
            </tr>
          </thead>
          <tbody>
            {result.violations.map((violation, index) => (
              <tr key={`${violation.pointer}-${index}`}>
                {/* The JSON Pointer is the whole point: it locates the problem
                    in the author's own document rather than describing it. */}
                <td className="mono whitespace-nowrap align-top text-[12.5px]">{violation.pointer}</td>
                <td className="text-[13px]">
                  {violation.message}
                  {violation.expected ? (
                    <span className="mt-1 block text-[12px] text-ink-3">Expected: {violation.expected}</span>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      <p className="mt-3 text-[12px] text-ink-3">
        Correlation ID <span className="mono">{result.correlationId}</span>
      </p>
    </div>
  );
}

function Registered({ result }: { result: Extract<Result, { kind: "registered" }> }) {
  return (
    <div role="status" className="card max-w-[80ch] border-l-[3px] p-4" style={{ borderLeftColor: "var(--accent)" }}>
      <b className="block font-medium">
        {result.created
          ? `${result.service.slug} is registered`
          : `${result.service.slug} was already registered with this exact manifest`}
      </b>
      <p className="mt-1.5 text-[13px] text-ink-2">
        {result.created
          ? "It has been scored against the policy rules and an audit entry was written, in the same transaction."
          : "Nothing was written. Re-submitting an unchanged manifest is a replay, not an edit, so its version did not move."}
      </p>
      <p className="mt-2.5 text-[13px]">
        <Link href={`/catalog/${result.service.slug}` as Route} className="underline" style={{ color: "var(--accent)" }}>
          Open {result.service.slug}
        </Link>
      </p>
    </div>
  );
}
