import { test, expect } from "@playwright/test";
import { rateLimit } from "../src/lib/rate";

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
