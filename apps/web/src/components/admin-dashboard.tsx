"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import {
  isSuperAdmin,
  plannedDateLabel,
  statusLabel,
  type Mission,
  type MissionPage,
} from "@/lib/types";
import { ErrorNotice, useUser } from "./workspace";

type Summary = {
  total: number;
  inProgress: number;
  pending: number;
  overdue: number;
  byStatus: Record<Mission["status"], number>;
};
const statuses: Mission["status"][] = [
  "DRAFT",
  "IN_PROGRESS",
  "PENDING_REVIEW",
  "APPROVED",
  "CLOSED",
];
const cards = [
  { key: "total", label: "Toutes les missions", href: "/missions" },
  {
    key: "inProgress",
    label: "En cours",
    href: "/missions?status=IN_PROGRESS",
  },
  {
    key: "pending",
    label: "À valider",
    href: "/missions?status=PENDING_REVIEW",
  },
  { key: "overdue", label: "En retard", href: "/missions?overdue=true" },
] as const;
export default function AdminDashboard() {
  const user = useUser();
  const [data, setData] = useState<{
    summary: Summary;
    priorities: MissionPage;
  } | null>(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!isSuperAdmin(user)) return;
    const abort = new AbortController();
    setError("");
    setData(null);
    async function read(url: string) {
      const response = await fetch(url, {
        signal: abort.signal,
        cache: "no-store",
      });
      if (!response.ok)
        throw new Error(
          response.status === 401
            ? "Votre session a expiré. Reconnectez-vous."
            : "Le tableau de bord est temporairement indisponible.",
        );
      return response.json();
    }
    Promise.all([
      read("/api/admin/summary"),
      read("/api/missions?overdue=true&sort=end&size=5"),
    ])
      .then(([summary, priorities]) => setData({ summary, priorities }))
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => abort.abort();
  }, [user, retry]);
  if (user && !isSuperAdmin(user))
    return <ErrorNotice message="Accès réservé au superadministrateur." />;
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">ADMINISTRATION</span>
          <h1>Tableau de bord</h1>
          <p>Les échéances à suivre et une vue globale de vos missions.</p>
        </div>
        <Link className="button secondary" href="/missions">
          Voir les missions
        </Link>
      </div>
      {error ? (
        <section className="panel panel-message">
          <ErrorNotice message={error} />
          <button
            className="button secondary"
            onClick={() => setRetry((n) => n + 1)}
          >
            Réessayer
          </button>
        </section>
      ) : !data ? (
        <p className="panel panel-message" role="status">
          Chargement du tableau de bord…
        </p>
      ) : (
        <>
          <div className="directory-stats dashboard-stats">
            {cards.map((card) => (
              <Link
                key={card.key}
                className="panel"
                href={card.href}
                aria-label={card.label}
              >
                <strong>{data.summary[card.key]}</strong>
                <span>{card.label}</span>
              </Link>
            ))}
          </div>
          <div className="dashboard-grid">
            <section className="panel" aria-labelledby="priorities-title">
              <div className="panel-title">
                <h2 id="priorities-title">À traiter en priorité</h2>
                <Link href="/missions?overdue=true&sort=end">
                  Voir tous les retards
                </Link>
              </div>
              <p className="dashboard-description">
                Les cinq échéances dépassées les plus anciennes.
              </p>
              {!data.priorities.items.length ? (
                <div className="empty">
                  <h3>Aucune mission en retard</h3>
                  <p>Les échéances dépassées apparaîtront ici.</p>
                </div>
              ) : (
                <ul className="dashboard-priorities">
                  {data.priorities.items.slice(0, 5).map((m) => (
                    <li key={m.id}>
                      <div>
                        <strong>{m.title}</strong>
                        <div className="dashboard-mission-meta">
                          <span
                            className={`status mission-status-${m.status.toLowerCase()}`}
                          >
                            {statusLabel(m.status)}
                          </span>
                          <span>
                            Fin prévue :{" "}
                            <time dateTime={m.plannedEndDate || undefined}>
                              {plannedDateLabel(m.plannedEndDate)}
                            </time>
                          </span>
                        </div>
                      </div>
                      <Link
                        className="button secondary compact"
                        aria-label={"Ouvrir " + m.title}
                        href={`/missions/${m.id}?returnTo=${encodeURIComponent("/missions/admin")}`}
                      >
                        Ouvrir
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <section className="panel" aria-labelledby="distribution-title">
              <div className="panel-title">
                <h2 id="distribution-title">Répartition par statut</h2>
              </div>
              {data.summary.total === 0 && (
                <p className="dashboard-description">
                  Aucune mission à afficher pour le moment.
                </p>
              )}
              <ul className="dashboard-distribution">
                {statuses.map((status) => (
                  <li key={status}>
                    <Link href={`/missions?status=${status}`}>
                      <span className="dashboard-bar-label">
                        <span>{statusLabel(status)}</span>
                        <strong>{data.summary.byStatus[status]}</strong>
                      </span>
                      <span className="dashboard-bar" aria-hidden="true">
                        <span
                          style={{
                            width: `${data.summary.total ? (data.summary.byStatus[status] / data.summary.total) * 100 : 0}%`,
                          }}
                        />
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          </div>
        </>
      )}
    </>
  );
}
