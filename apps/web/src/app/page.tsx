import { redirect } from "next/navigation";
import { session } from "@/lib/auth";
import { demoAccounts, demoEnabled } from "@/lib/demo";
import { BrandLockup } from "@/components/brand";
import {
  ShieldCheck,
  ArrowUpRight,
  Check,
  ClipboardCheck,
  BriefcaseBusiness,
  BadgeCheck,
  UserRound,
} from "lucide-react";
const demoIcons = {
  manager: BriefcaseBusiness,
  auditor: ClipboardCheck,
  validator: BadgeCheck,
  auditee: UserRound,
};
export const dynamic = "force-dynamic";
export default async function Home({
  searchParams,
}: {
  searchParams: Promise<{ error?: string; signedOut?: string }>;
}) {
  let connected = false;
  try {
    connected = !!(await session());
  } catch {}
  if (connected) redirect("/missions");
  const query = await searchParams;
  return (
    <main className="login">
      <section className="login-story">
        <a className="brand" href="/">
          <BrandLockup />
        </a>
        <div className="login-copy">
          <span className="eyebrow light">VOTRE ESPACE D’AUDIT</span>
          <h1>
            De la visibilité.
            <br />
            De la méthode.
            <br />
            <em>De la confiance.</em>
          </h1>
          <p>
            Centralisez vos missions et donnez à chaque audit un cadre clair, du
            premier contrôle à la dernière action.
          </p>
          <div className="login-points">
            <span>
              <Check size={17} /> Un espace commun
            </span>
            <span>
              <Check size={17} /> Des accès maîtrisés
            </span>
          </div>
        </div>
        <footer>
          La rigueur au service de vos décisions.<span>01 / MISSIONS</span>
        </footer>
      </section>
      <section className="login-panel">
        <div className="login-card">
          <span className="icon-tile">
            <ShieldCheck size={28} />
          </span>
          <span className="eyebrow">BIENVENUE</span>
          <h2>
            Votre prochain audit
            <br />
            commence ici.
          </h2>
          <p>
            Connectez-vous à votre espace pour retrouver vos missions et votre
            équipe.
          </p>
          {query.error && (
            <div role="alert" className="notice error">
              {query.error === "unavailable"
                ? "Le service de connexion est temporairement indisponible. Réessayez dans un instant."
                : "La connexion n’a pas abouti. Veuillez réessayer."}
            </div>
          )}
          {query.signedOut && (
            <div role="status" className="notice">
              Vous êtes déconnecté de cet espace.
            </div>
          )}
          <a className="button primary login-button" href="/auth/login">
            Se connecter <ArrowUpRight size={19} />
          </a>
          {demoEnabled() ? (
            <form className="demo-entry" method="post" action="/auth/demo">
              <span className="demo-entry-title">
                Ou explorez la démonstration
              </span>
              {Object.entries(demoAccounts).map(([account, role]) => {
                const Icon = demoIcons[account as keyof typeof demoIcons];
                return (
                  <button
                    key={account}
                    className="button secondary demo-button"
                    name="account"
                    value={account}
                  >
                    <Icon size={18} />
                    <span>
                      <strong>Entrer comme {role.label.toLowerCase()}</strong>
                      <small>{role.detail}</small>
                    </span>
                    <ArrowUpRight size={16} />
                  </button>
                );
              })}
              <div className="login-note">
                Comptes fictifs partagés, sans mot de passe à saisir
              </div>
            </form>
          ) : (
            <div className="login-note">
              <ShieldCheck size={15} /> Accès réservé aux utilisateurs autorisés
            </div>
          )}
        </div>
        <span className="login-bottom">
          AuditSphere · Espace de travail interne
        </span>
      </section>
    </main>
  );
}
