import { test, expect } from "@playwright/test";
import { randomBytes } from "node:crypto";
import { rateLimit } from "../src/lib/rate";
import { encryptSession, decryptSession } from "../src/lib/session-crypto";
import { NextRequest } from "next/server";
import { proxy } from "../src/proxy";

test("login limits isolate trusted proxy client addresses", () => {
  const previous = process.env.TRUSTED_PROXY_IP_HEADER;
  process.env.TRUSTED_PROXY_IP_HEADER = "x-real-ip";
  try {
    const request = (ip: string) => new Request("http://localhost:3000/auth/login", { headers: { "x-real-ip": ip } });
    for (let i = 0; i < 30; i++) expect(rateLimit(request("192.0.2.11"))).toBeNull();
    expect(rateLimit(request("192.0.2.11"))?.status).toBe(429);
    expect(rateLimit(request("192.0.2.12"))).toBeNull();
  } finally {
    if (previous === undefined) delete process.env.TRUSTED_PROXY_IP_HEADER;
    else process.env.TRUSTED_PROXY_IP_HEADER = previous;
  }
});

test("HTTPS login fails closed without a trusted proxy address", () => {
  const previousOrigin = process.env.APP_ORIGIN;
  const previousHeader = process.env.TRUSTED_PROXY_IP_HEADER;
  process.env.APP_ORIGIN = "https://audit.example";
  delete process.env.TRUSTED_PROXY_IP_HEADER;
  try {
    const request = new Request("https://audit.example/auth/login", { headers: { "x-forwarded-for": "192.0.2.44" } });
    expect(rateLimit(request)?.status).toBe(503);
  } finally {
    if (previousOrigin === undefined) delete process.env.APP_ORIGIN;
    else process.env.APP_ORIGIN = previousOrigin;
    if (previousHeader === undefined) delete process.env.TRUSTED_PROXY_IP_HEADER;
    else process.env.TRUSTED_PROXY_IP_HEADER = previousHeader;
  }
});

test("session tokens are authenticated and absent from stored JSON", () => {
  const previous = process.env.SESSION_ENCRYPTION_KEY;
  process.env.SESSION_ENCRYPTION_KEY = randomBytes(32).toString("base64");
  try {
    const original = { subject: "user-1", accessToken: "access-secret", refreshToken: "refresh-secret" };
    const stored = encryptSession(original, "session-hash");
    expect(JSON.stringify(stored)).not.toContain("access-secret");
    expect(JSON.stringify(stored)).not.toContain("refresh-secret");
    expect(decryptSession<typeof original>(stored, "session-hash")).toEqual(original);
    expect(() => decryptSession<typeof original>(stored, "another-hash")).toThrow();
    expect(() => decryptSession<typeof original>({ ...stored, tokens: { ...stored.tokens, tag: randomBytes(16).toString("base64") } }, "session-hash")).toThrow();
  } finally {
    if (previous === undefined) delete process.env.SESSION_ENCRYPTION_KEY;
    else process.env.SESSION_ENCRYPTION_KEY = previous;
  }
});

test("CSP has a fresh nonce and HTTPS responses carry HSTS", () => {
  const previous = process.env.APP_ORIGIN;
  const previousIssuer = process.env.OIDC_ISSUER;
  process.env.APP_ORIGIN = "https://audit.example";
  process.env.OIDC_ISSUER = "https://identity.example/realms/auditsphere";
  try {
    const first = proxy(new NextRequest("https://audit.example/missions"));
    const second = proxy(new NextRequest("https://audit.example/missions"));
    const policy = first.headers.get("Content-Security-Policy") || "";
    expect(policy).toContain("default-src 'self'");
    expect(policy).toContain("frame-ancestors 'none'");
    expect(policy).toContain("object-src 'none'");
    expect(policy).toContain("base-uri 'self'");
    expect(policy).toContain("form-action 'self' https://identity.example;");
    expect(policy).toMatch(/script-src[^;]*'nonce-[^']+'/);
    expect(second.headers.get("Content-Security-Policy")).not.toBe(policy);
    expect(first.headers.get("Strict-Transport-Security")).toBe("max-age=31536000");
  } finally {
    if (previous === undefined) delete process.env.APP_ORIGIN;
    else process.env.APP_ORIGIN = previous;
    if (previousIssuer === undefined) delete process.env.OIDC_ISSUER;
    else process.env.OIDC_ISSUER = previousIssuer;
  }
});
