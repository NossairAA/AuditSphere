import "server-only";
import { cookies } from "next/headers";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { Pool } from "pg";
import * as oidc from "openid-client";
import { createRemoteJWKSet, jwtVerify } from "jose";

export const origin = process.env.APP_ORIGIN || "http://localhost:3000";
const issuer =
  process.env.OIDC_ISSUER || "http://localhost:8180/realms/auditsphere";
export const secure = new URL(origin).protocol === "https:";
export const cookieName = secure
  ? "__Host-auditsphere-session"
  : "auditsphere-session";
export const transactionCookie = secure
  ? "__Host-auditsphere-login"
  : "auditsphere-login";
export const cookieOptions = {
  httpOnly: true,
  secure,
  sameSite: "lax" as const,
  path: "/",
};
const globalDb = globalThis as unknown as { sessionPool?: Pool };
export const pool =
  globalDb.sessionPool ??
  new Pool({
    connectionString: process.env.SESSION_DATABASE_URL,
    max: 8,
    connectionTimeoutMillis: 5000,
  });
globalDb.sessionPool = pool;
export const random = () => randomBytes(32).toString("base64url");
export const hash = (value: string) =>
  createHash("sha256").update(value).digest("hex");
let configuration: Promise<oidc.Configuration> | undefined;
export async function oidcConfig() {
  if (!configuration) {
    const local =
      process.env.ALLOW_INSECURE_LOCAL === "true" &&
      new URL(issuer).hostname === "localhost" &&
      new URL(origin).hostname === "localhost";
    if ((!secure || new URL(issuer).protocol !== "https:") && !local)
      throw new Error("HTTPS required");
    configuration = oidc.discovery(
      new URL(issuer),
      process.env.OIDC_CLIENT_ID!,
      process.env.OIDC_CLIENT_SECRET!,
      undefined,
      local
        ? { execute: [oidc.allowInsecureRequests], timeout: 10 }
        : { timeout: 10 },
    );
    configuration.catch(() => {
      configuration = undefined;
    });
  }
  return configuration;
}
const jwks = createRemoteJWKSet(
  new URL(issuer + "/protocol/openid-connect/certs"),
);
export type Identity = { subject: string; name: string; roles: string[] };
export type Session = Identity & {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
  csrf: string;
  absoluteExpiry: number;
};
export async function identity(token: string): Promise<Identity> {
  const { payload } = await jwtVerify(token, jwks, {
    issuer,
    audience: "auditsphere-api",
  });
  if (!payload.sub) throw new Error("Subject required");
  const roles = (payload.realm_access as { roles?: unknown })?.roles;
  return {
    subject: payload.sub,
    name: String(payload.name || payload.preferred_username || "Utilisateur"),
    roles: Array.isArray(roles)
      ? roles.filter((r): r is string => typeof r === "string")
      : [],
  };
}
export async function saveSession(
  tokens: oidc.TokenEndpointResponse,
): Promise<string> {
  if (!tokens.refresh_token || !tokens.expires_in)
    throw new Error("Incomplete token response");
  const id = random();
  const data: Session = {
    ...(await identity(tokens.access_token)),
    accessToken: tokens.access_token,
    refreshToken: tokens.refresh_token,
    expiresAt: Date.now() + tokens.expires_in * 1000,
    csrf: random(),
    absoluteExpiry: Date.now() + 8 * 3600_000,
  };
  await pool.query(
    "INSERT INTO web_sessions(id_hash,kind,data,expires_at) VALUES($1,'session',$2,now()+interval '30 minutes')",
    [hash(id), data],
  );
  return id;
}
// A row lock serializes refreshes across tabs and BFF processes.
export async function session(): Promise<Session | null> {
  const id = (await cookies()).get(cookieName)?.value;
  if (!id || !/^[A-Za-z0-9_-]{43}$/.test(id)) return null;
  const db = await pool.connect();
  try {
    await db.query("BEGIN");
    const row = await db.query(
      "SELECT data,expires_at FROM web_sessions WHERE id_hash=$1 AND kind='session' FOR UPDATE",
      [hash(id)],
    );
    if (!row.rowCount) {
      await db.query("COMMIT");
      return null;
    }
    let data = row.rows[0].data as Session;
    if (
      new Date(row.rows[0].expires_at).getTime() <= Date.now() ||
      data.absoluteExpiry <= Date.now()
    ) {
      await db.query("DELETE FROM web_sessions WHERE id_hash=$1", [hash(id)]);
      await db.query("COMMIT");
      return null;
    }
    if (data.expiresAt <= Date.now() + 30_000) {
      try {
        const tokens = await oidc.refreshTokenGrant(
          await oidcConfig(),
          data.refreshToken,
        );
        data = {
          ...data,
          ...(await identity(tokens.access_token)),
          accessToken: tokens.access_token,
          refreshToken: tokens.refresh_token || data.refreshToken,
          expiresAt: Date.now() + (tokens.expires_in || 0) * 1000,
        };
      } catch {
        await db.query("DELETE FROM web_sessions WHERE id_hash=$1", [hash(id)]);
        await db.query("COMMIT");
        return null;
      }
    }
    await db.query(
      "UPDATE web_sessions SET data=$2, expires_at=$3 WHERE id_hash=$1",
      [
        hash(id),
        data,
        new Date(Math.min(data.absoluteExpiry, Date.now() + 30 * 60_000)),
      ],
    );
    await db.query("COMMIT");
    return data;
  } catch (error) {
    await db.query("ROLLBACK");
    throw error;
  } finally {
    db.release();
  }
}
export function validMutation(
  request: Request,
  data: Session,
  token: string | null,
) {
  if (request.headers.get("origin") !== origin || !token) return false;
  const a = Buffer.from(token),
    b = Buffer.from(data.csrf);
  return a.length === b.length && timingSafeEqual(a, b);
}
export function json(body: unknown, status = 200) {
  return Response.json(body, {
    status,
    headers: { "Cache-Control": "no-store" },
  });
}
