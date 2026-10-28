import { createHash } from "node:crypto";
import { isIP } from "node:net";
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
function rejected() {
  return Response.json(
    { error: "Trop de requêtes. Réessayez dans une minute." },
    { status: 429, headers: { "Retry-After": "60", "Cache-Control": "no-store" } },
  );
}

const TRUSTED_HEADERS = new Set(["cf-connecting-ip", "x-real-ip", "x-forwarded-for"]);

export function clientAddress(request: Request) {
  // Trusted reverse proxies must overwrite these single-value headers and block direct BFF access.
  // A list such as "cf-connecting-ip,x-real-ip" uses the first header present: Cloudflare always
  // overwrites cf-connecting-ip on the public name, Traefik sets x-real-ip on the tailnet.
  const headers = (process.env.TRUSTED_PROXY_IP_HEADER || "").split(",").map((h) => h.trim().toLowerCase());
  if (!headers[0] || !headers.every((h) => TRUSTED_HEADERS.has(h))) return "direct";
  const header = headers.find((h) => request.headers.has(h)) ?? headers[0];
  const value = request.headers.get(header)?.trim() || "";
  return isIP(value) ? value : "unknown";
}

export function rateLimit(request: Request, maxBody = 32768) {
  const path = new URL(request.url).pathname;
  const address = clientAddress(request);
  if (
    (process.env.APP_ORIGIN?.startsWith("https://") && address === "direct") ||
    (process.env.TRUSTED_PROXY_IP_HEADER && address === "unknown")
  )
    return Response.json({ error: "Configuration du proxy indisponible" }, { status: 503 });
  // Protect the session store before authentication without coupling ordinary users
  // to the much smaller login and anonymous budgets.
  if (!allow("preauth:" + address, 6000)) return rejected();
  if (path === "/auth/login" && !allow("login:" + address, 30)) return rejected();
  if (path === "/auth/callback" && !allow("callback:" + address, 60)) return rejected();
  const len = request.headers.get("content-length");
  if (len && Number(len) > maxBody)
    return Response.json(
      { error: "Formulaire trop volumineux" },
      { status: 413 },
    );
  return null;
}

export function rateLimitAnonymous(request: Request) {
  return allow("anonymous:" + clientAddress(request), 60) ? null : rejected();
}

export function rateLimitSession(data: { csrf: string }) {
  const key = createHash("sha256").update(data.csrf).digest("hex");
  return allow("session:" + key, 120) ? null : rejected();
}
