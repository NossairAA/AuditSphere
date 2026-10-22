"use client";
import { useUnsavedGuard } from "./unsaved-guard";
import { useState, type FormEvent } from "react";
import { type Mission, isSuperAdmin } from "@/lib/types";
import { ErrorNotice, useUser } from "./workspace";
export type ControlResult = {
  outcome: "CONFORMING" | "NON_CONFORMING" | "NOT_APPLICABLE";
  comment: string;
  authorSubject: string;
  authorName: string;
  recordedAt: string;
};
const labels = {
  CONFORMING: "Conforme",
  NON_CONFORMING: "Non conforme",
  NOT_APPLICABLE: "Non applicable",
};
export default function ControlResultForm({
  mission,
  controlId,
  result,
  onSaved,
}: {
  mission: Mission;
  controlId: string;
  result: ControlResult | null;
  onSaved: () => void;
}) {
  const user = useUser();
  const [dirty, setDirty] = useState(false);
  useUnsavedGuard(dirty);
  const [editing, setEditing] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const allowed =
    mission.status === "IN_PROGRESS" &&
    (isSuperAdmin(user) ||
      (!!user &&
        user.roles.includes("AUDITOR") &&
        mission.assigneeSubjects.includes(user.subject)));
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const fields = new FormData(event.currentTarget);
    setBusy(true);
    setError("");
    try {
      const response = await fetch(`/api/missions/${mission.id}/results`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          "x-csrf-token": user!.csrf,
        },
        body: JSON.stringify({
          version: mission.version,
          controlId,
          outcome: fields.get("outcome"),
          comment: fields.get("comment"),
        }),
        signal: AbortSignal.timeout(10000),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error);
      setDirty(false);
      setEditing(false);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Enregistrement impossible.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="control-result">
      <h5>Résultat du contrôle</h5>
      {result ? (
        <>
          <strong>{labels[result.outcome]}</strong>
          <p className="preserve">{result.comment}</p>
          <p className="program-rating">
            Renseigné par {result.authorName}, le{" "}
            {new Date(result.recordedAt).toLocaleString("fr-FR")}
          </p>
        </>
      ) : (
        <p>Non renseigné</p>
      )}
      {error && (
        <>
          <ErrorNotice message={error} />
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={() => {
              setEditing(false);
              setError("");
              onSaved();
            }}
          >
            Actualiser la fiche
          </button>
        </>
      )}
      {allowed && !editing && (
        <button className="button secondary" onClick={() => setEditing(true)}>
          {result ? "Corriger le résultat" : "Renseigner le résultat"}
        </button>
      )}
      {allowed && editing && (
        <form
          onChange={() => setDirty(true)}
          className="program-editor"
          onSubmit={save}
        >
          <fieldset disabled={busy}>
            <label htmlFor={`outcome-${controlId}`}>Conclusion</label>
            <select
              id={`outcome-${controlId}`}
              name="outcome"
              required
              defaultValue={result?.outcome || ""}
            >
              <option value="" disabled>
                Choisir une conclusion
              </option>
              {Object.entries(labels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
            <label htmlFor={`comment-${controlId}`}>
              Justification du résultat
            </label>
            <textarea
              id={`comment-${controlId}`}
              name="comment"
              required
              maxLength={4000}
              rows={3}
              defaultValue={result?.comment}
            />
            <div className="program-editor-actions">
              <button className="button primary" type="submit">
                {busy ? "Enregistrement…" : "Enregistrer le résultat"}
              </button>
              <button
                className="button secondary"
                type="button"
                onClick={() => {
                  if (!dirty || confirm("Abandonner les modifications ?")) {
                    setDirty(false);
                    setEditing(false);
                  }
                }}
              >
                Annuler
              </button>
            </div>
          </fieldset>
        </form>
      )}
      {mission.status === "DRAFT" && (
        <p className="program-rating">
          La saisie sera disponible après le démarrage de la mission.
        </p>
      )}
    </div>
  );
}
