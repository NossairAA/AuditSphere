import { NextResponse } from "next/server";
import * as oidc from "openid-client";
import {
  origin,
  random,
  hash,
  pool,
  oidcConfig,
  transactionCookie,
  cookieOptions,
} from "@/lib/auth";
export async function GET() {
  try {
    const config = await oidcConfig(),
      id = random(),
      verifier = oidc.randomPKCECodeVerifier();
    const state = oidc.randomState(),
      nonce = oidc.randomNonce();
    await pool.query("DELETE FROM web_sessions WHERE expires_at < now()");
    await pool.query(
      "INSERT INTO web_sessions(id_hash,kind,data,expires_at) VALUES($1,'oauth',$2,now()+interval '10 minutes')",
      [hash(id), { verifier, state, nonce }],
    );
    const url = oidc.buildAuthorizationUrl(config, {
      redirect_uri: origin + "/auth/callback",
      scope: "openid profile email",
      state,
      nonce,
      code_challenge: await oidc.calculatePKCECodeChallenge(verifier),
      code_challenge_method: "S256",
    });
    const response = NextResponse.redirect(url);
    response.cookies.set(transactionCookie, id, {
      ...cookieOptions,
      maxAge: 600,
    });
    return response;
  } catch {
    return NextResponse.redirect(new URL("/?error=unavailable", origin));
  }
}
