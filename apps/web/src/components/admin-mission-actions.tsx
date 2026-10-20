"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { type Mission } from "@/lib/types";
import { ErrorNotice, useUser } from "./workspace";
const labels: Record<string, string> = {
  APPROVE: "Approuver par dérogation",
  CLOSE: "Clôturer sans suivre le parcours normal",
  REOPEN: "Réouvrir la mission",
  DELETE: "Supprimer la mission",
};
type Entry = {
  action: string;
  operation: string | null;
  actor_subject: string;
  reason: string;
  previous_status: string | null;
  resulting_status: string | null;
  occurred_at: string;
};
export default function AdminMissionActions({
  mission,
  onUpdated,
}: {
  mission: Mission;
  onUpdated: (m: Mission) => void;
}) {
  const user = useUser(),
    router = useRouter();
  const [action, setAction] = useState(""),
    [reason, setReason] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const [entries, setEntries] = useState<Entry[] | null>(null);
  if (!user?.roles.includes("SUPER_ADMIN")) return null;
  const allowed = (value: string) =>
    value === "DELETE" ||
    (mission.status !== "DRAFT" &&
      (value === "APPROVE"
        ? !["APPROVED", "CLOSED"].includes(mission.status)
        : value === "CLOSE"
          ? mission.status !== "CLOSED"
          : mission.status !== "IN_PROGRESS"));
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!confirmed || !reason.trim() || !allowed(action)) return;
    setBusy(true);
    setError("");
    try {
      const r = await fetch(`/api/missions/${mission.id}/override`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          "x-csrf-token": user!.csrf,
        },
        body: JSON.stringify({ version: mission.version, action, reason }),
        signal: AbortSignal.timeout(10000),
      });
      if (!r.ok) throw new Error((await r.json()).error);
      if (r.status === 204) {
        router.replace("/missions");
        router.refresh();
      } else onUpdated(await r.json());
      setAction("");
      setReason("");
      setConfirmed(false);
      setEntries(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Dérogation impossible.");
    } finally {
      setBusy(false);
    }
  }
  async function history() {
    setBusy(true);
    setError("");
    try {
      const r = await fetch(`/api/missions/${mission.id}/action-log`, {
        cache: "no-store",
        signal: AbortSignal.timeout(10000),
      });
      if (!r.ok) throw new Error((await r.json()).error);
      setEntries(await r.json());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Journal indisponible.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section
      className="admin-mission-panel"
      aria-label="Administration de la mission"
    >
      <details>
        <summary>Dérogations du superadministrateur</summary>
        <p>
          Ces actions peuvent contourner la validation, y compris pour votre
          propre mission. Chaque intervention est journalisée.
        </p>
        {error && (
          <>
            <ErrorNotice message={error} />
            <button
              className="button secondary"
              onClick={() => window.location.reload()}
            >
              Recharger la fiche
            </button>
          </>
        )}
        <form className="review-form" onSubmit={submit}>
          <label>
            Action administrative
            <select
              required
              value={action}
              disabled={busy}
              onChange={(e) => {
                setAction(e.target.value);
                setConfirmed(false);
              }}
            >
              <option value="">Choisir une action</option>
              {Object.entries(labels).map(([key, label]) => (
                <option key={key} value={key} disabled={!allowed(key)}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          {mission.status === "DRAFT" && (
            <p>
              Définissez les dates et démarrez le brouillon avant une
              approbation ou une clôture.
            </p>
          )}
          {action && (
            <>
              <p>
                {action === "DELETE"
                  ? "La mission disparaîtra pour tous les utilisateurs. Les données et le journal seront conservés, sans restauration disponible dans l’interface."
                  : action === "REOPEN"
                    ? "La mission repassera en cours et nécessitera une nouvelle validation."
                    : action === "APPROVE"
                      ? "Votre décision remplacera celle du validateur désigné, même si vous êtes propriétaire de la mission."
                      : "La mission sera clôturée, même sans approbation préalable."}
              </p>
              <label>
                Motif de la dérogation
                <textarea
                  required
                  rows={4}
                  maxLength={2000}
                  disabled={busy}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
              <label className="admin-confirm">
                <input
                  type="checkbox"
                  required
                  checked={confirmed}
                  disabled={busy}
                  onChange={(e) => setConfirmed(e.target.checked)}
                />
                Je confirme cette dérogation sur « {mission.title} ».
              </label>
              <button
                className="button secondary"
                disabled={busy || !confirmed || !reason.trim()}
              >
                Exécuter la dérogation
              </button>
            </>
          )}
        </form>
        <button className="button secondary" disabled={busy} onClick={history}>
          Consulter le journal des actions
        </button>
        {entries && (
          <div aria-label="Journal des actions">
            {entries.length === 0 ? (
              <p>Aucune action journalisée.</p>
            ) : (
              <ul>
                {entries.map((e, i) => (
                  <li key={i}>
                    <strong>{labels[e.operation || ""] || e.action}</strong>
                    <p>{e.reason}</p>
                    <small>
                      {new Date(e.occurred_at).toLocaleString("fr-FR")} · Auteur
                      : {e.actor_subject}
                      {e.previous_status &&
                        ` · ${e.previous_status} → ${e.resulting_status}`}
                    </small>
                  </li>
                ))}
              </ul>
            )}
            <p>Les 100 dernières actions sont affichées.</p>
          </div>
        )}
      </details>
    </section>
  );
}
