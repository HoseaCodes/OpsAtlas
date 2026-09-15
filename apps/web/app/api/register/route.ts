import { NextResponse } from "next/server";
import { currentToken } from "@/lib/session";

/**
 * Proxies a manifest submission to the control plane.
 *
 * The browser never talks to the control plane directly: that keeps its URL out
 * of the client bundle and means there is no CORS policy to configure, get
 * wrong, and then loosen under deadline pressure.
 *
 * The problem document is passed through unchanged, status and all, so the form
 * renders the control plane's own violations rather than a paraphrase of them.
 * Paraphrasing here would be a second place for error text to drift.
 */
export async function POST(request: Request) {
  const document = await request.text();
  const sourcePath = new URL(request.url).searchParams.get("sourcePath") ?? "service.yaml";

  const baseUrl = process.env.OPSATLAS_API_URL ?? "http://localhost:8080";
  const target = `${baseUrl}/api/v1/services?sourcePath=${encodeURIComponent(sourcePath)}`;

  // Registering names an actor in the audit log, so it is the reader's own
  // token that goes upstream - not a credential belonging to the console.
  const token = await currentToken();
  if (!token) {
    return NextResponse.json(
      {
        type: "https://opsatlas.ambitiousconcepts.io/problems/unauthenticated",
        title: "Authentication required",
        status: 401,
        detail: "Your session has ended. Sign in again, then submit the manifest.",
        correlationId: "not-issued",
      },
      { status: 401 },
    );
  }

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: "POST",
      headers: { "Content-Type": "application/yaml", Authorization: `Bearer ${token}` },
      body: document,
      cache: "no-store",
    });
  } catch {
    return NextResponse.json(
      {
        type: "https://opsatlas.ambitiousconcepts.io/problems/upstream-unreachable",
        title: "The control plane is unreachable",
        status: 503,
        detail:
          "The console could not reach the control plane. Check that it is running: make dev.",
        correlationId: "not-issued",
      },
      { status: 503 },
    );
  }

  const body = await upstream.text();
  return new NextResponse(body, {
    status: upstream.status,
    headers: {
      "Content-Type": upstream.headers.get("Content-Type") ?? "application/json",
    },
  });
}
