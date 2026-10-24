import type { NextConfig } from "next";
const config: NextConfig = {
  poweredByHeader: false,
  agentRules: false,
  serverExternalPackages: ["pg"],
  // Page requests need CSP, but never a large body buffer before route admission.
  experimental: { proxyClientMaxBodySize: 32768 },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          ...(process.env.APP_ORIGIN?.startsWith("https://")
            ? [{ key: "Strict-Transport-Security", value: "max-age=31536000" }]
            : []),
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "same-origin" },
          { key: "Cache-Control", value: "no-store" },
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=()",
          },
        ],
      },
    ];
  },
};
export default config;
