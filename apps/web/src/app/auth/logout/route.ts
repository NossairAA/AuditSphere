import { rateLimit, rateLimitAnonymous, rateLimitSession } from "@/lib/rate";
import { limitedText, BodyError } from "@/lib/body";
import { NextRequest, NextResponse } from "next/server";
import * as oidc from "openid-client";
import {
  session,
  validMutation,
  json,
  pool,
  hash,
  cookieName,
  cookieOptions,
  origin,
  oidcConfig,
} from "@/lib/auth";
export async function POST(request: NextRequest) {
  const rejection = rateLimit(request);
  if (rejection) return rejection;
  try {
    const data = await session();
    if (!data) {
      const anonymousLimit = rateLimitAnonymous(request);
      if (anonymousLimit) return anonymousLimit;
      return json({ error: "Session expirée" }, 401);
    }
    const limitedSession = rateLimitSession(data);
    if (limitedSession) return limitedSession;
    const form = new URLSearchParams(await limitedText(request, 4096));
    if (!validMutation(request, data, String(form.get("csrf") || "")))
      return json({ error: "Requête refusée" }, 403);
    const id = request.cookies.get(cookieName)!.value;
    await pool.query("DELETE FROM web_sessions WHERE id_hash=$1", [hash(id)]);
    let destination: URL;
    try {
      // A demo session has no refresh token and no Keycloak session in the browser (ADR-0035).
      if (data.demo) throw new Error("No Keycloak session");
      const config = await oidcConfig();
      await oidc.tokenRevocation(config, data.refreshToken, {
        token_type_hint: "refresh_token",
      });
      destination = oidc.buildEndSessionUrl(config, {
        client_id: process.env.OIDC_CLIENT_ID!,
        post_logout_redirect_uri: origin + "/",
      });
    } catch {
      destination = new URL("/?signedOut=1", origin);
    }
    const response = NextResponse.redirect(destination, 303);
    response.cookies.set(cookieName, "", { ...cookieOptions, maxAge: 0 });
    return response;
  } catch (error) {
    if (error instanceof BodyError)
      return json({ error: "Corps de requête refusé" }, error.status);
    return json({ error: "Déconnexion indisponible. Réessayez." }, 503);
  }
}
