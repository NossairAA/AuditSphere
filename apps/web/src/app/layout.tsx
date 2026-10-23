import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {
  title: "AuditSphere | Missions d’audit",
  description: "Votre espace de travail pour des audits clairs et traçables.",
};
export const dynamic = "force-dynamic";
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="fr">
      <body>{children}</body>
    </html>
  );
}
