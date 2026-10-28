import type { Metadata } from "next";
import "./globals.css";
import { demoProfile } from "@/lib/demo";
export const metadata: Metadata = {
  title: "AuditSphere | Missions d’audit",
  description: "Votre espace de travail pour des audits clairs et traçables.",
};
export const dynamic = "force-dynamic";
export default function Layout({ children }: { children: React.ReactNode }) {
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
      </body>
    </html>
  );
}
