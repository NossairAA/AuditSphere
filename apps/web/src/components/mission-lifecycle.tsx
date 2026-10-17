"use client";
import { useState } from "react";
import { ErrorNotice, useUser } from "./workspace";
import { type Mission, plannedDateLabel, dateLabel } from "@/lib/types";
export default function MissionLifecycle({
  mission,
  onUpdated,
}: {
  mission: Mission;
  onUpdated: (m: Mission) => void;
}) {
  const user = useUser();
  const canEdit =
    user?.subject === mission.ownerSubject &&
    user.roles.includes("AUDIT_MANAGER");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [confirmClose, setConfirmClose] = useState(false);
  const [start, setStart] = useState(mission.plannedStartDate || "");
  const [end, setEnd] = useState(mission.plannedEndDate || "");
  const [notice, setNotice] = useState("");
  async function save(resource: "schedule" | "status", body: object) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch(`/api/missions/${mission.id}/${resource}`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          "x-csrf-token": user!.csrf,
        },
        body: JSON.stringify({ version: mission.version, ...body }),
        signal: AbortSignal.timeout(10000),
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error);
      onUpdated(result);
      setNotice(
        resource === "schedule" ? "Dates enregistrées." : "Statut mis à jour.",
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Modification impossible.");
    } finally {
      setBusy(false);
      setConfirmClose(false);
    }
  }
  async function reload() {
    setBusy(true);
    try {
      const r = await fetch(`/api/missions/${mission.id}`, {
        cache: "no-store",
        signal: AbortSignal.timeout(10000),
      });
      const body = await r.json();
      if (!r.ok) throw new Error(body.error);
      onUpdated(body);
      setError("");
      setStart(body.plannedStartDate || "");
      setEnd(body.plannedEndDate || "");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Rechargement impossible.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section
      className="panel participants"
      aria-label="Planification et statut"
    >
      <h2>Planification et statut</h2>
      {error && (
        <>
          <ErrorNotice message={error} />
          <button className="button secondary" disabled={busy} onClick={reload}>
            Recharger la fiche
          </button>
        </>
      )}
      {notice && <p role="status">{notice}</p>}
      {canEdit && mission.status !== "CLOSED" ? (
        <form
          className="lifecycle-form"
          onSubmit={(e) => {
            e.preventDefault();
            void save("schedule", {
              plannedStartDate: start,
              plannedEndDate: end,
            });
          }}
        >
          <label>
            Date de début prévue
            <input
              type="date"
              required
              value={start}
              max="9999-12-31"
              onChange={(e) => setStart(e.target.value)}
              disabled={busy}
            />
          </label>
          <label>
            Date de fin prévue
            <input
              type="date"
              required
              value={end}
              min={start || "0001-01-01"}
              max="9999-12-31"
              onChange={(e) => setEnd(e.target.value)}
              disabled={busy}
            />
          </label>
          <button className="button secondary" disabled={busy}>
            Enregistrer les dates
          </button>
        </form>
      ) : (
        <p>
          Début prévu : {plannedDateLabel(mission.plannedStartDate)} · Fin
          prévue : {plannedDateLabel(mission.plannedEndDate)}
        </p>
      )}
      {mission.startedAt && <p>Démarrée le {dateLabel(mission.startedAt)}</p>}
      {mission.closedAt && <p>Clôturée le {dateLabel(mission.closedAt)}</p>}
      {canEdit && mission.status === "DRAFT" && (
        <div className="form-actions">
          {!mission.plannedStartDate && (
            <p>Enregistrez les deux dates prévues avant de démarrer.</p>
          )}
          <button
            className="button primary"
            disabled={
              busy || !mission.plannedStartDate || !mission.plannedEndDate
            }
            onClick={() => save("status", { status: "IN_PROGRESS" })}
          >
            Démarrer la mission
          </button>
        </div>
      )}
      {canEdit && mission.status === "IN_PROGRESS" && (
        <div className="form-actions">
          {confirmClose ? (
            <>
              <p>La clôture est définitive pour ce parcours. Confirmer ?</p>
              <button
                className="button secondary"
                disabled={busy}
                onClick={() => setConfirmClose(false)}
              >
                Annuler
              </button>
              <button
                className="button primary"
                disabled={busy}
                onClick={() => save("status", { status: "CLOSED" })}
              >
                Confirmer la clôture
              </button>
            </>
          ) : (
            <button
              className="button primary"
              disabled={busy}
              onClick={() => setConfirmClose(true)}
            >
              Clôturer la mission
            </button>
          )}
        </div>
      )}
    </section>
  );
}
