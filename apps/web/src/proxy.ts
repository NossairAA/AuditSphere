import { randomBytes } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";

export function proxy(request: NextRequest) {
  const nonce = randomBytes(16).toString("base64");
  const identityOrigin = new URL(
    process.env.OIDC_ISSUER || "http://localhost:8180/realms/auditsphere",
  ).origin;
  const directives = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${process.env.NODE_ENV === "development" ? " 'unsafe-eval'" : ""}`,
    `style-src 'self' 'nonce-${nonce}'`,
    "style-src-attr 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    `form-action 'self' ${identityOrigin}`,
    "frame-ancestors 'none'",
  ];
  const policy = directives.join("; ");
  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("Content-Security-Policy", policy);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", policy);
  if (process.env.APP_ORIGIN?.startsWith("https://"))
    response.headers.set("Strict-Transport-Security", "max-age=31536000");
  return response;
}

export const config = { matcher: "/((?!api(?:/|$)|auth(?:/|$)|_next/static|_next/image|favicon.ico).*)" };
