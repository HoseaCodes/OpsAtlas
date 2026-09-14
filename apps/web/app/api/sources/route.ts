import { NextResponse } from "next/server";
import { currentToken } from "@/lib/session";

/**
 * Proxies source management to the control plane.
 *
 * Same reasoning as app/api/register: the browser never talks to the control
 * plane directly, so its address stays server-side and there is no CORS policy
 * to configure, get wrong, and then loosen under deadline pressure. The problem
 * document is passed through unchanged so the page renders the control plane's
 * own violations rather than a paraphrase.
 */
const baseUrl = () => process.env.OPSATLAS_API_URL ?? "http://localhost:8080";

async function forward(path: string, init: RequestInit): Promise<NextResponse> {
  // The reader's own token, forwarded. The console holds no credential of its
  // own on purpose: one would make every write in the audit log read as "the
  // console did it" (ADR 0013).
  const token = await currentToken();
  if (!token) {
    return NextResponse.json(
      {
        type: "https://opsatlas.ambitiousconcepts.io/problems/unauthenticated",
        title: "Authentication required",
        status: 401,
        detail: "Your session has ended. Sign in again to continue.",
        correlationId: "not-issued",
      },
      { status: 401 },
    );
  }

  let upstream: Response;
  try {
    upstream = await fetch(`${baseUrl()}${path}`, {
      ...init,
      headers: { ...(init.headers ?? {}), Authorization: `Bearer ${token}` },
      cache: "no-store",
    });
  } catch {
    return NextResponse.json(
      {
        type: "https://opsatlas.ambitiousconcepts.io/problems/upstream-unreachable",
        title: "The control plane is unreachable",
        status: 503,
        detail: "The console could not reach the control plane. Check that it is running: make dev.",
        correlationId: "not-issued",
      },
      { status: 503 },
    );
  }

  if (upstream.status === 204) {
    return new NextResponse(null, { status: 204 });
  }

  const body = await upstream.text();
  return new NextResponse(body, {
    status: upstream.status,
    headers: { "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" },
  });
}

export async function POST(request: Request) {
  const url = new URL(request.url);
  const action = url.searchParams.get("action");
  const id = url.searchParams.get("id");

  // Watching a new repository.
  if (!action) {
    return forward("/api/v1/sources", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: await request.text(),
    });
  }

  if (!id || !["sync", "enable", "disable"].includes(action)) {
    return NextResponse.json(
      { title: "Unknown action", status: 400, detail: `'${action}' is not an action this endpoint performs.` },
      { status: 400 },
    );
  }

  return forward(`/api/v1/sources/${encodeURIComponent(id)}/${action}`, { method: "POST" });
}

export async function DELETE(request: Request) {
  const id = new URL(request.url).searchParams.get("id");
  if (!id) {
    return NextResponse.json({ title: "No source given", status: 400, detail: "An id is required." }, { status: 400 });
  }
  return forward(`/api/v1/sources/${encodeURIComponent(id)}`, { method: "DELETE" });
}
