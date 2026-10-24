import { test, expect } from "@playwright/test";
import { randomBytes } from "node:crypto";
import { request as httpRequest } from "node:http";
import { rateLimit } from "../src/lib/rate";
import { encryptSession, decryptSession } from "../src/lib/session-crypto";
import { NextRequest } from "next/server";
import { proxy, config as proxyConfig } from "../src/proxy";
import nextConfig from "../next.config";
import { unstable_doesMiddlewareMatch } from "next/experimental/testing/server";

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


test("CSP proxy excludes upload and auth bodies before route admission", async () => {
  for (const url of ["/api", "/api/session", "/api/missions/a/controls/b/evidence", "/auth", "/auth/login", "/auth/logout"]) {
    expect(unstable_doesMiddlewareMatch({ config: proxyConfig, nextConfig, url })).toBe(false);
  }
  for (const url of ["/", "/missions", "/missions/new", "/missions/a"]) {
    expect(unstable_doesMiddlewareMatch({ config: proxyConfig, nextConfig, url })).toBe(true);
  }
  expect(nextConfig.experimental?.proxyClientMaxBodySize).toBe(32768);
  const previous = process.env.APP_ORIGIN;
  process.env.APP_ORIGIN = "https://audit.example";
  try {
    const rules = await nextConfig.headers!();
    expect(rules.find((rule) => rule.source === "/:path*")?.headers).toContainEqual({
      key: "Strict-Transport-Security", value: "max-age=31536000",
    });
  } finally {
    if (previous === undefined) delete process.env.APP_ORIGIN;
    else process.env.APP_ORIGIN = previous;
  }
});


test("anonymous upload is rejected before its body finishes", async () => {
  const status = await new Promise<number>((resolve, reject) => {
    const upload = httpRequest("http://localhost:3000/api/missions/00000000-0000-0000-0000-000000000001/controls/00000000-0000-0000-0000-000000000002/evidence", {
      method: "POST",
      headers: { "Content-Type": "multipart/form-data; boundary=security-test" },
    }, (response) => {
      response.resume();
      resolve(response.statusCode || 0);
      upload.destroy();
    });
    upload.on("error", reject);
    upload.setTimeout(3000, () => upload.destroy(new Error("Upload body was buffered before authentication")));
    // Deliberately keep the chunked body unfinished. Authentication must reject it now.
    upload.write("--security-test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.pdf\"\r\n\r\n%PDF-1.4");
  });
  expect(status).toBe(401);
});
