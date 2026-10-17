"use client";
import Link from "next/link";
import UnreadCount from "./unread-count";
import { usePathname } from "next/navigation";
import { useEffect, useState, createContext, useContext } from "react";
import {
  Bell,
  Layers,
  ClipboardList,
  ShieldCheck,
  LogOut,
  ArrowRight,
} from "lucide-react";
export type User = {
  subject: string;
  name: string;
  roles: string[];
  csrf: string;
};
const UserContext = createContext<User | null>(null);
export const useUser = () => useContext(UserContext);
export default function Workspace({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const notifications = pathname === "/missions/notifications";
  const [user, setUser] = useState<User | null>(null),
    [error, setError] = useState("");
  useEffect(() => {
    fetch("/api/session", { cache: "no-store" })
      .then(async (r) => {
        if (!r.ok)
          throw new Error(
            r.status === 401
              ? "Votre session a expiré."
              : "Votre espace est temporairement indisponible.",
          );
        setUser(await r.json());
      })
      .catch((e) => setError(e.message));
  }, []);
  return (
    <div className="workspace">
      <aside className="sidebar">
        <Link className="brand" href="/missions">
          <span className="brand-mark">
            <Layers size={22} />
          </span>
          AuditSphere<span className="brand-dot">.</span>
        </Link>
        <div className="workspace-label">ESPACE DE TRAVAIL</div>
        <nav>
          <Link
            href="/missions"
            className={"nav-link" + (!notifications ? " active" : "")}
          >
            <ClipboardList size={19} /> Missions d’audit{" "}
            <ArrowRight size={15} />
          </Link>
        </nav>
        <div className="sidebar-note">
          <ShieldCheck size={21} />
          <strong>
            Le bon accès,
            <br />
            au bon périmètre.
          </strong>
          <p>Votre espace rassemble les missions qui vous sont confiées.</p>
        </div>
        <div className="profile">
          <span className="avatar">
            {user?.name.slice(0, 1).toUpperCase() || "A"}
          </span>
          <div>
            <strong>{user?.name || "Connexion…"}</strong>
            <small>
              {user?.roles.includes("AUDIT_MANAGER")
                ? "Responsable d’audit"
                : "Auditeur"}
            </small>
          </div>
          {user && (
            <form action="/auth/logout" method="post">
              <input type="hidden" name="csrf" value={user.csrf} />
              <button
                className="icon-button"
                aria-label="Se déconnecter"
                title="Se déconnecter"
              >
                <LogOut size={18} />
              </button>
            </form>
          )}
        </div>
      </aside>
      <div className="workspace-body">
        <header className="topbar">
          <span>
            Organisation <span className="slash">/</span>{" "}
            <strong>Audit interne</strong>
          </span>
          <div className="topbar-actions">
            <span className="private-label">
              <span /> Espace privé
            </span>
            <Link
              href="/missions/notifications"
              className={
                "header-notifications" + (notifications ? " active" : "")
              }
              aria-label="Notifications"
              aria-current={notifications ? "page" : undefined}
              title="Notifications"
            >
              <Bell size={19} aria-hidden="true" />
              <span className="notification-label">Notifications</span>
              {user && <UnreadCount />}
            </Link>
          </div>
        </header>
        <main className="content">
          {error ? (
            <div className="empty panel">
              <ShieldCheck size={32} />
              <h1>{error}</h1>
              <p>Reconnectez-vous pour accéder à vos missions.</p>
              <a className="button primary" href="/auth/login">
                Se reconnecter
              </a>
            </div>
          ) : !user ? (
            <div className="loading" role="status">
              Ouverture de votre espace…
            </div>
          ) : (
            <UserContext.Provider value={user}>{children}</UserContext.Provider>
          )}
        </main>
        <footer className="workspace-footer">
          AuditSphere{" "}
          <span>Clarté dans les missions. Confiance dans les décisions.</span>
        </footer>
      </div>
    </div>
  );
}
export function ErrorNotice({ message }: { message: string }) {
  return (
    <div className="notice error" role="alert">
      {message}
      {message.includes("session") && (
        <>
          {" "}
          <a href="/auth/login">Se reconnecter</a>
        </>
      )}
    </div>
  );
}
