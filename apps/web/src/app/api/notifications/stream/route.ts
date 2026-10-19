import { session, json } from "@/lib/auth";
import { rateLimit } from "@/lib/rate";
export const dynamic = "force-dynamic";
export const runtime = "nodejs";
export async function GET(request: Request) {
  const rejected = rateLimit(request);
  if (rejected) return rejected;
  try {
    const user = await session();
    if (!user) return json({ error: "Session expirée" }, 401);
    const upstream = await fetch(
      new URL(
        "/notifications/stream",
        process.env.API_ORIGIN || "http://localhost:8080",
      ),
      {
        headers: {
          Authorization: "Bearer " + user.accessToken,
          Accept: "text/event-stream",
        },
        cache: "no-store",
        signal: AbortSignal.any([request.signal, AbortSignal.timeout(30000)]),
      },
    );
    if (
      !upstream.ok ||
      !upstream.headers.get("content-type")?.includes("text/event-stream")
    ) {
      await upstream.body?.cancel();
      return json(
        { error: "Actualisation en direct indisponible" },
        upstream.ok ? 503 : upstream.status,
      );
    }
    return new Response(upstream.body, {
      headers: {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-store, no-transform",
        "X-Accel-Buffering": "no",
        "X-Content-Type-Options": "nosniff",
      },
    });
  } catch {
    return json({ error: "Actualisation en direct indisponible" }, 503);
  }
}
