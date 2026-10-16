import { createHash } from "node:crypto";
const buckets = new Map<string, { count: number; until: number }>();
function allow(key: string, limit: number) {
  const now = Date.now();
  let b = buckets.get(key);
  if (!b || b.until <= now) {
    if (buckets.size >= 10000) {
      for (const [k, v] of buckets) if (v.until <= now) buckets.delete(k);
      if (buckets.size >= 10000) return false;
    }
    b = { count: 0, until: now + 60000 };
    buckets.set(key, b);
  }
  return ++b.count <= limit;
}
export function rateLimit(request: Request) {
  const cookie = (request.headers.get("cookie") || "")
    .split(";")
    .map((x) => x.trim())
    .find(
      (x) =>
        x.startsWith("__Host-auditsphere-session=") ||
        x.startsWith("auditsphere-session="),
    )
    ?.split("=")[1];
  const key = cookie
    ? createHash("sha256").update(cookie).digest("hex")
    : "anonymous";
  if (
    !allow("global", 600) ||
    !allow("session:" + key, 120) ||
    (new URL(request.url).pathname === "/auth/login" && !allow("login", 30))
  ) {
    return Response.json(
      { error: "Trop de requêtes. Réessayez dans une minute." },
      {
        status: 429,
        headers: { "Retry-After": "60", "Cache-Control": "no-store" },
      },
    );
  }
  const len = request.headers.get("content-length");
  if (len && Number(len) > 32768)
    return Response.json(
      { error: "Formulaire trop volumineux" },
      { status: 413 },
    );
  return null;
}
