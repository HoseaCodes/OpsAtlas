import { NextResponse } from "next/server";
import { currentToken } from "@/lib/session";

/**
 * Updating and removing one registered service.
 *
 * The browser never talks to the control plane directly, for the same reason as
 * `/api/register`: its URL stays out of the client bundle and there is no CORS
 * policy to configure, get wrong, and then loosen under deadline pressure.
 *
 * Both methods require `If-Match`, and this handler will not invent one. The
 * version comes from the page the reader was looking at, so an edit or a delete
 * applies to the service they actually saw — and a concurrent change is refused
 * with the control plane's own 412 rather than silently overwritten.
 *
 * Problem documents pass through unchanged, status and all. Paraphrasing here
 * would be a second place for error text to drift from the control plane's.
 */

const baseUrl = () => process.env.OPSATLAS_API_URL ?? "http://localhost:8080";

function problem(status: number, type: string, title: string, detail: string) {
  return NextResponse.json(
    {
      type: `https://opsatlas.ambitiousconcepts.io/problems/${type}`,
      title,
      status,
      detail,
      correlationId: "not-issued",
    },
    { status },
  );
}

/**
 * Forwards one request upstream as the signed-in reader.
 *
 * It is deliberately the reader's own token rather than a credential belonging
 * to the console: every one of these writes names an actor in the audit log, and
 * a console credential would make each of them read as "the console did it".
 */
async function forward(
  slug: string,
  init: { method: "PUT" | "DELETE"; ifMatch: string | null; body?: string; contentType?: string },
): Promise<Response> {
  if (!init.ifMatch) {
    return problem(
      428,
      "precondition-required",
      "Precondition required",
      init.method === "DELETE"
        ? "This delete needs the version you read, so it cannot remove a service somebody has just changed. Reload the page and try again."
        : "This update needs the version you read, so it cannot overwrite a change you have not seen. Reload the page and try again.",
    );
  }

  const token = await currentToken();
  if (!token) {
    return problem(
      401,
      "unauthenticated",
      "Authentication required",
      "Your session has ended. Sign in again, then retry.",
    );
  }

  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
    "If-Match": init.ifMatch,
  };
  if (init.contentType) headers["Content-Type"] = init.contentType;

  let upstream: Response;
  try {
    upstream = await fetch(`${baseUrl()}/api/v1/services/${encodeURIComponent(slug)}`, {
      method: init.method,
      headers,
      body: init.body,
      cache: "no-store",
    });
  } catch {
    return problem(
      503,
      "upstream-unreachable",
      "The control plane is unreachable",
      "The console could not reach the control plane. Check that it is running.",
    );
  }

  // 204 carries no body, and returning one would make it not a 204.
  if (upstream.status === 204) return new NextResponse(null, { status: 204 });

  const body = await upstream.text();
  return new NextResponse(body, {
    status: upstream.status,
    headers: { "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" },
  });
}

export async function PUT(request: Request, { params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  return forward(slug, {
    method: "PUT",
    ifMatch: request.headers.get("If-Match"),
    body: await request.text(),
    // YAML, because the control plane's PUT consumes YAML — and YAML 1.2 is a
    // superset of JSON, so a manifest pasted in either form is accepted.
    contentType: "application/yaml",
  });
}

export async function DELETE(request: Request, { params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  return forward(slug, { method: "DELETE", ifMatch: request.headers.get("If-Match") });
}
