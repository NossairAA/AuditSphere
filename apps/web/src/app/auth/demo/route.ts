import { rateLimit } from "@/lib/rate";
import { limitedText } from "@/lib/body";
import { NextRequest, NextResponse } from "next/server";
import {
  origin,
  hash,
  pool,
  cookieName,
  cookieOptions,
  saveSession,
  demoTokens,
} from "@/lib/auth";
import { demoEnabled, isDemoAccount } from "@/lib/demo";
// One-click entry of the demo profile (ADR-0035). The visitor never sees nor types a password.
export async function POST(request: NextRequest) {
  const rejection = rateLimit(request);
  if (rejection) return rejection;
  const failed = (error: string) =>
    NextResponse.redirect(new URL("/?error=" + error, origin), 303);
  // A form posted from another site must not open a session in the visitor's browser.
  if (!demoEnabled() || request.headers.get("origin") !== origin)
    return failed("login");
  try {
    const account = new URLSearchParams(await limitedText(request, 256)).get(
      "account",
    );
    if (!isDemoAccount(account)) return failed("login");
    const tokens = await demoTokens(account);
    const previous = request.cookies.get(cookieName)?.value;
    if (previous)
      await pool.query("DELETE FROM web_sessions WHERE id_hash=$1", [
        hash(previous),
      ]);
    const response = NextResponse.redirect(new URL("/missions", origin), 303);
    response.cookies.set(cookieName, await saveSession(tokens, account), {
      ...cookieOptions,
      maxAge: 8 * 3600,
    });
    return response;
  } catch {
    return failed("unavailable");
  }
}
