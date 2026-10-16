"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { Bell, RefreshCw } from "lucide-react";
import { ErrorNotice } from "@/components/workspace";
import { dateLabel } from "@/lib/types";
type NotificationPage = {
  items: {
    id: string;
    missionId: string;
    occurredAt: string;
    receivedAt: string;
  }[];
  page: number;
  size: number;
  hasNext: boolean;
};
export default function Notifications() {
  const [data, setData] = useState<NotificationPage | null>(null);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const timer = setInterval(() => {
      if (document.visibilityState === "visible" && page === 0)
        setRefresh((v) => v + 1);
    }, 30000);
    return () => clearInterval(timer);
  }, [page]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    fetch("/api/notifications?page=" + page, {
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (r) => {
        const body = await r.json();
        if (!r.ok) throw new Error(body.error);
        setData(body);
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [page, refresh]);
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">VOTRE ACTIVITÉ</span>
          <h1>
            Notifications<span className="green">.</span>
          </h1>
          <p>
            Retrouvez vos nouvelles affectations. La première page s’actualise
            toutes les 30 secondes.
          </p>
        </div>
        <button
          className="button secondary"
          disabled={loading}
          onClick={() => setRefresh((v) => v + 1)}
        >
          <RefreshCw size={18} /> Actualiser
        </button>
      </div>
      <section className="panel" aria-label="Vos notifications">
        <div className="panel-title">
          <div>
            <h2>Nouvelles affectations</h2>
            <p>
              Une affectation passée ne garantit pas l’accès actuel à la
              mission.
            </p>
          </div>
          <Bell size={24} />
        </div>
        {error ? (
          <div className="panel-message">
            <ErrorNotice message={error} />
          </div>
        ) : !data ? (
          <div className="loading" role="status">
            Chargement des notifications…
          </div>
        ) : !data.items.length ? (
          <div className="empty">
            <Bell size={36} />
            <h3>Aucune notification</h3>
            <p>Vos prochaines affectations apparaîtront ici.</p>
          </div>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>ACTIVITÉ</th>
                  <th>DATE</th>
                  <th>MISSION</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((n) => (
                  <tr key={n.id}>
                    <td>
                      <strong>Vous avez été affecté à une mission</strong>
                    </td>
                    <td>{dateLabel(n.occurredAt)}</td>
                    <td>
                      <Link
                        className="mission-link"
                        href={"/missions/" + n.missionId}
                      >
                        Consulter la mission{" "}
                        <span className="sr-only">{n.missionId}</span>
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="table-footer">
          <span>Page {page + 1}</span>
          <div>
            <button
              className="button secondary"
              disabled={loading || page === 0}
              onClick={() => {
                setData(null);
                setPage((p) => p - 1);
              }}
            >
              Précédente
            </button>
            <button
              className="button secondary"
              disabled={loading || !!error || !data?.hasNext}
              onClick={() => {
                setData(null);
                setPage((p) => p + 1);
              }}
            >
              Suivante
            </button>
          </div>
        </div>
      </section>
    </>
  );
}
