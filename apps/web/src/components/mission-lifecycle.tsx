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
  const [comment, setComment] = useState("");
  const canReview =
    user?.roles.includes("AUDIT_VALIDATOR") &&
    user.subject === mission.validatorSubject &&
    user.subject !== mission.ownerSubject;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [confirmClose, setConfirmClose] = useState(false);
  const [start, setStart] = useState(mission.plannedStartDate || "");
  const [end, setEnd] = useState(mission.plannedEndDate || "");
  const [notice, setNotice] = useState("");
  async function save(
    resource: "schedule" | "status" | "submission" | "review",
    body: object,
  ) {
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
      {canEdit && ["DRAFT", "IN_PROGRESS"].includes(mission.status) ? (
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
      {mission.reviewDecision && (
        <div>
          <h3>
            {mission.reviewDecision === "APPROVED"
              ? "Mission approuvée"
              : "Corrections demandées"}
          </h3>
          <p className="preserve">{mission.reviewComment}</p>
          {mission.reviewedAt && (
            <p>Décision du {dateLabel(mission.reviewedAt)}</p>
          )}
        </div>
      )}
      {mission.status === "PENDING_REVIEW" && (
        <p>
          En attente de la décision du validateur. La planification et la
          désignation sont verrouillées.
        </p>
      )}
      {canEdit && mission.status === "IN_PROGRESS" && (
        <div className="form-actions">
          <p>
            {mission.validatorSubject
              ? "La mission doit être approuvée avant sa clôture."
              : "Désignez un validateur avant de soumettre la mission."}
          </p>
          <button
            className="button primary"
            disabled={busy || !mission.validatorSubject}
            onClick={() => save("submission", {})}
          >
            Soumettre à validation
          </button>
        </div>
      )}
      {canReview && mission.status === "PENDING_REVIEW" && (
        <div className="review-form">
          <label>
            Commentaire de validation
            <textarea
              rows={5}
              required
              aria-describedby="review-comment-help"
              placeholder="Expliquez votre décision ou les corrections attendues…"
              value={comment}
              maxLength={2000}
              disabled={busy}
              onChange={(e) => setComment(e.target.value)}
            />
          </label>
          <p id="review-comment-help" className="review-comment-help">
            Commentaire obligatoire · 2 000 caractères maximum
          </p>
          <div className="form-actions">
            <button
              className="button primary"
              disabled={busy || !comment.trim()}
              onClick={() => save("review", { decision: "APPROVED", comment })}
            >
              Approuver la mission
            </button>
            <button
              className="button secondary"
              disabled={busy || !comment.trim()}
              onClick={() => save("review", { decision: "REJECTED", comment })}
            >
              Demander des corrections
            </button>
          </div>
        </div>
      )}
      {canEdit && mission.status === "APPROVED" && (
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
