import type { Metadata } from "next";
import { headers } from "next/headers";
import "./globals.css";
import { demoProfile } from "@/lib/demo";
export const metadata: Metadata = {
  title: "AuditSphere | Missions d’audit",
  description: "Votre espace de travail pour des audits clairs et traçables.",
};
export const dynamic = "force-dynamic";
export default async function Layout({
  children,
}: {
  children: React.ReactNode;
}) {
  const analyticsToken = process.env.CF_ANALYTICS_TOKEN;
  const umamiWebsiteId = process.env.UMAMI_WEBSITE_ID;
  // Umami ignores other hosts, such as the tailnet name or a local server.
  const umamiDomain = process.env.APP_ORIGIN
    ? new URL(process.env.APP_ORIGIN).hostname
    : undefined;
  const nonce = analyticsToken || umamiWebsiteId
    ? ((await headers()).get("x-nonce") ?? undefined)
    : undefined;
  return (
    <html lang="fr">
      <body className={demoProfile() ? "demo" : undefined}>
        {children}
        {demoProfile() && (
          <div className="demo-banner" role="note">
            Environnement de démonstration : données fictives, réinitialisées
            chaque nuit.
            <span className="demo-signature">
              Conçu et développé par{" "}
              <a href="https://github.com/NossairAA" rel="author">
                Nossair AIT AHMED
              </a>
            </span>
          </div>
        )}
        {analyticsToken && (
          <script
            type="module"
            nonce={nonce}
            src="https://static.cloudflareinsights.com/beacon.min.js"
            data-cf-beacon={JSON.stringify({ token: analyticsToken })}
          />
        )}
        {umamiWebsiteId && (
          <script
            defer
            nonce={nonce}
            src="https://cloud.umami.is/script.js"
            data-website-id={umamiWebsiteId}
            data-domains={umamiDomain}
            data-exclude-search="true"
          />
        )}
      </body>
    </html>
  );
}
