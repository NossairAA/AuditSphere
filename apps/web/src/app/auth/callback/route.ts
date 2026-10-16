import { NextRequest, NextResponse } from "next/server";
import * as oidc from "openid-client";
import {
  origin,
  hash,
  pool,
  oidcConfig,
  cookieName,
  transactionCookie,
  cookieOptions,
  saveSession,
} from "@/lib/auth";
export async function GET(request: NextRequest) {
  const response = NextResponse.redirect(new URL("/missions", origin));
  response.cookies.set(transactionCookie, "", { ...cookieOptions, maxAge: 0 });
  try {
    const id = request.cookies.get(transactionCookie)?.value;
    if (!id) throw new Error("Missing transaction");
    const transaction = await pool.query(
      "DELETE FROM web_sessions WHERE id_hash=$1 AND kind='oauth' AND expires_at>now() RETURNING data",
      [hash(id)],
    );
    if (!transaction.rowCount) throw new Error("Expired transaction");
    const data = transaction.rows[0].data;
    const callback = new URL("/auth/callback", origin);
    callback.search = request.nextUrl.search;
    const tokens = await oidc.authorizationCodeGrant(
      await oidcConfig(),
      callback,
      {
        pkceCodeVerifier: data.verifier,
        expectedState: data.state,
        expectedNonce: data.nonce,
        idTokenExpected: true,
      },
    );
    const previous = request.cookies.get(cookieName)?.value;
    if (previous)
      await pool.query("DELETE FROM web_sessions WHERE id_hash=$1", [
        hash(previous),
      ]);
    response.cookies.set(cookieName, await saveSession(tokens), {
      ...cookieOptions,
      maxAge: 8 * 3600,
    });
    return response;
  } catch {
    const failed = NextResponse.redirect(new URL("/?error=login", origin));
    failed.cookies.set(transactionCookie, "", { ...cookieOptions, maxAge: 0 });
    return failed;
  }
}
