"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { ArrowRight, Bell, CheckCheck, RefreshCw } from "lucide-react";
import { ErrorNotice, useUser } from "@/components/workspace";

type NotificationPage = {
  items: {
    id: string;
    missionId: string;
    missionTitle: string | null;
    occurredAt: string;
    receivedAt: string;
    readAt: string | null;
    eventType: string;
    actionId: string | null;
  }[];
  page: number;
  size: number;
  hasNext: boolean;
};
export default function Notifications() {
  const user = useUser();
  const router = useRouter();
  const [pending, setPending] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState("");
  const [markingAll, setMarkingAll] = useState(false);
  const [notice, setNotice] = useState("");
  const [data, setData] = useState<NotificationPage | null>(null);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const liveRefresh = () => setRefresh((value) => value + 1);
    window.addEventListener("notifications-live", liveRefresh);
    const timer = setInterval(() => {
      if (document.visibilityState === "visible" && page === 0)
        setRefresh((v) => v + 1);
    }, 30000);
    return () => {
      clearInterval(timer);
      window.removeEventListener("notifications-live", liveRefresh);
    };
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
        window.dispatchEvent(new Event("notifications-changed"));
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [page, refresh]);
  async function markAllRead() {
    if (pending || markingAll) return;
    setMarkingAll(true);
    setMutationError("");
    setNotice("");
    try {
      const r = await fetch("/api/notifications/read-all", {
        method: "PUT",
        headers: { "x-csrf-token": user?.csrf || "" },
        signal: AbortSignal.timeout(10000),
      });
      const body = await r.json();
      if (!r.ok) throw new Error(body.error);
      // Server dates are reloaded with the list, the local copy only hides the unread state.
      setData((old) =>
        old
          ? {
              ...old,
              items: old.items.map((n) =>
                n.readAt ? n : { ...n, readAt: new Date().toISOString() },
              ),
            }
          : old,
      );
      setNotice(
        body.count === 0
          ? "Aucune notification non lue."
          : body.count === 1
            ? "1 notification marquée comme lue."
            : `${body.count} notifications marquées comme lues.`,
      );
      window.dispatchEvent(new Event("notifications-changed"));
      setRefresh((v) => v + 1);
    } catch (e) {
      setMutationError(
        e instanceof Error && e.message
          ? e.message
          : "Impossible de marquer les notifications comme lues.",
      );
    } finally {
      setMarkingAll(false);
    }
  }
  // An auditee never sees the mission: an action notification is only marked read until the
  // action page exists (F-006 lot 7).
  const isAction = (n: NotificationPage["items"][number]) =>
    n.eventType === "audit.action.assigned.v1";
  async function openNotification(n: NotificationPage["items"][number]) {
    if (pending || markingAll) return;
    if (n.readAt) {
      router.push(isAction(n) ? `/actions/${n.actionId}` : `/missions/${n.missionId}`);
      return;
    }
    const id = n.id;
    setPending(id);
    setMutationError("");
    try {
      const r = await fetch(`/api/notifications/${id}/read`, {
        method: "PUT",
        headers: { "x-csrf-token": user?.csrf || "" },
        signal: AbortSignal.timeout(10000),
      });
      const body = await r.json();
      if (!r.ok) throw new Error(body.error);
      setData((old) =>
        old
          ? {
              ...old,
              items: old.items.map((n) =>
                n.id === id ? { ...n, readAt: body.readAt } : n,
              ),
            }
          : old,
      );
      window.dispatchEvent(new Event("notifications-changed"));
      router.push(isAction(n) ? `/actions/${n.actionId}` : `/missions/${n.missionId}`);
    } catch (e) {
      setMutationError(
        e instanceof Error
          ? e.message
          : "Impossible de marquer la notification comme lue.",
      );
    } finally {
      setPending(null);
    }
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">VOTRE ACTIVITÉ</span>
          <h1>
            Notifications<span className="green">.</span>
          </h1>
          <p>
            Retrouvez vos affectations et demandes de validation. La première
            page se met à jour en direct. Les plus récentes apparaissent en
            premier.
          </p>
        </div>
        <div className="heading-actions">
          <button
            className="button secondary"
            disabled={markingAll || pending !== null}
            onClick={() => void markAllRead()}
          >
            <CheckCheck size={18} />{" "}
            {markingAll ? "Marquage…" : "Tout marquer comme lu"}
          </button>
          <button
            className="button secondary"
            disabled={loading}
            onClick={() => setRefresh((v) => v + 1)}
          >
            <RefreshCw size={18} /> Actualiser
          </button>
        </div>
      </div>
      <section className="panel" aria-label="Vos notifications">
        <div className="panel-title">
          <div>
            <h2>Votre activité</h2>
            <p>
              Cliquez sur une notification pour ouvrir la mission et la marquer
              comme lue.
            </p>
          </div>
          <Bell size={24} />
        </div>
        {notice && (
          <div className="panel-message">
            <p className="notice" role="status">
              {notice}
            </p>
          </div>
        )}
        {mutationError && (
          <div className="panel-message">
            <ErrorNotice message={mutationError} />
          </div>
        )}
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
            <p>Vos prochaines notifications apparaîtront ici.</p>
          </div>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>ACTIVITÉ</th>
                  <th>DATE</th>
                  <th>MISSION</th>
                  <th className="notification-action-cell">
                    <span className="sr-only">Action</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((n) => (
                  <tr
                    key={n.id}
                    className={`notification-row${n.readAt ? "" : " notification-unread"}`}
                    onClick={(event) => {
                      if (!(event.target as HTMLElement).closest("a"))
                        void openNotification(n);
                    }}
                  >
                    <td>
                      <strong>
                        {n.eventType === "audit.mission.review-requested.v1"
                          ? "Une mission attend votre validation"
                          : isAction(n)
                            ? "Une action corrective vous est confiée"
                            : "Vous avez été affecté à une mission"}
                      </strong>
                      {!n.readAt && <span className="status">Non lue</span>}
                    </td>
                    <td>
                      {new Date(n.receivedAt).toLocaleString("fr-FR", {
                        dateStyle: "short",
                        timeStyle: "short",
                      })}
                    </td>
                    <td>
                      <strong>
                        {isAction(n)
                          ? "Action corrective"
                          : n.missionTitle || "Mission indisponible"}
                      </strong>
                    </td>
                    <td className="notification-action-cell">
                      <Link
                        className="notification-open"
                        aria-label={
                          isAction(n)
                            ? "Ouvrir l’action corrective confiée"
                            : `Ouvrir la mission : ${n.missionTitle || "Mission indisponible"}`
                        }
                        href={
                          isAction(n)
                            ? "/actions/" + n.actionId
                            : "/missions/" + n.missionId
                        }
                        prefetch={false}
                        aria-disabled={pending !== null}
                        onClick={(event) => {
                          event.preventDefault();
                          void openNotification(n);
                        }}
                      >
                        <span className="notification-open-action">
                          {pending === n.id
                            ? "Ouverture…"
                            : "Ouvrir"}
                          <ArrowRight size={16} aria-hidden="true" />
                        </span>
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
