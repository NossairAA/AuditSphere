"use client";
import { useEffect, useState, type FormEvent } from "react";
import { ClipboardCheck, Plus } from "lucide-react";
import { canManageMission, type Mission } from "@/lib/types";
import { ErrorNotice, useUser } from "./workspace";

type Risk = {
  id: string;
  title: string;
  description: string;
  probability: number;
  impact: number;
};
type Control = {
  id: string;
  objective: string;
  procedure: string;
  riskIds: string[];
};
type Program = { mission: Mission; risks: Risk[]; controls: Control[] };
type Editor =
  { kind: "risks"; value?: Risk } | { kind: "controls"; value?: Control };
const probabilities = [
  "Très faible",
  "Faible",
  "Modérée",
  "Élevée",
  "Très élevée",
];
const impacts = ["Mineur", "Limité", "Significatif", "Majeur", "Critique"];
export default function AuditProgram({
  mission,
  onUpdated,
}: {
  mission: Mission;
  onUpdated: (mission: Mission) => void;
}) {
  const user = useUser();
  const [program, setProgram] = useState<Program | null>(null);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  const unlocked = ["DRAFT", "IN_PROGRESS"].includes(mission.status);
  const canEdit = canManageMission(user, mission) && unlocked;
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    fetch(`/api/missions/${mission.id}/program`, {
      cache: "no-store",
      signal: abort.signal,
    })
      .then(async (r) => {
        const data = await r.json();
        if (!r.ok) throw new Error(data.error);
        return data as Program;
      })
      .then((data) => {
        setProgram(data);
        setError("");
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [mission.id, mission.version, retry]);
  function open(next: Editor) {
    setError("");
    setNotice("");
    setEditor(next);
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editor || !program) return;
    const values = new FormData(event.currentTarget);
    const body =
      editor.kind === "risks"
        ? {
            title: values.get("title"),
            description: values.get("description"),
            probability: Number(values.get("probability")),
            impact: Number(values.get("impact")),
          }
        : {
            objective: values.get("objective"),
            procedure: values.get("procedure"),
            riskIds: values.getAll("riskIds"),
          };
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const r = await fetch(`/api/missions/${mission.id}/${editor.kind}`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          "x-csrf-token": user!.csrf,
        },
        body: JSON.stringify({
          ...body,
          id: editor.value?.id,
          version: program.mission.version,
        }),
        signal: AbortSignal.timeout(10000),
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data.error);
      setProgram(data);
      onUpdated(data.mission);
      setEditor(null);
      setNotice("Programme enregistré.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Enregistrement impossible.");
    } finally {
      setBusy(false);
    }
  }
  async function reload() {
    setBusy(true);
    try {
      const r = await fetch(`/api/missions/${mission.id}`, {
        cache: "no-store",
        signal: AbortSignal.timeout(10000),
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data.error);
      onUpdated(data);
      setEditor(null);
      setRetry((n) => n + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Actualisation impossible.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section
      className="panel mission-program"
      id="mission-programme"
      tabIndex={-1}
      aria-label="Programme d’audit"
    >
      <div className="section-label">
        <ClipboardCheck size={20} aria-hidden="true" />
        <h2>Programme d’audit</h2>
      </div>
      <p>Identifiez les risques et préparez les vérifications à réaliser.</p>
      {!unlocked && (
        <p className="program-lock">
          Programme en lecture seule pendant la validation et après approbation.
        </p>
      )}
      {error && <ErrorNotice message={error} />}
      {error && (
        <button className="button secondary" disabled={busy} onClick={reload}>
          Actualiser le programme
        </button>
      )}
      {notice && <p role="status">{notice}</p>}
      {loading && <p role="status">Chargement du programme…</p>}
      {program && (
        <>
          <div className="program-section-heading">
            <h3>
              Risques <span>({program.risks.length})</span>
            </h3>
            {canEdit && (
              <button
                className="button secondary"
                disabled={busy || loading || program.risks.length >= 100}
                onClick={() => open({ kind: "risks" })}
              >
                <Plus size={16} /> Ajouter un risque
              </button>
            )}
          </div>
          {!program.risks.length && (
            <p className="program-empty">
              Aucun risque défini. Commencez par identifier ce qui pourrait
              compromettre les objectifs de la mission.
            </p>
          )}
          <div className="program-items">
            {program.risks.map((r) => (
              <article key={r.id} className="program-item" aria-label={r.title}>
                <h4>{r.title}</h4>
                <p className="preserve">{r.description}</p>
                <p className="program-rating">
                  Probabilité : {r.probability}/5 ·{" "}
                  {probabilities[r.probability - 1]}
                  <br />
                  Impact : {r.impact}/5 · {impacts[r.impact - 1]}
                </p>
                {canEdit && (
                  <button
                    className="button secondary"
                    disabled={busy || loading}
                    onClick={() => open({ kind: "risks", value: r })}
                    aria-label={`Modifier le risque ${r.title}`}
                  >
                    Modifier
                  </button>
                )}
              </article>
            ))}
          </div>
          <div className="program-section-heading">
            <h3>
              Contrôles <span>({program.controls.length})</span>
            </h3>
            {canEdit && (
              <button
                className="button secondary"
                disabled={
                  busy ||
                  loading ||
                  !program.risks.length ||
                  program.controls.length >= 100
                }
                onClick={() => open({ kind: "controls" })}
              >
                <Plus size={16} /> Ajouter un contrôle
              </button>
            )}
          </div>
          {!program.controls.length && (
            <p className="program-empty">
              {program.risks.length
                ? "Aucun contrôle préparé. Définissez comment vérifier les risques identifiés."
                : "Ajoutez un risque pour pouvoir préparer un contrôle."}
            </p>
          )}
          <div className="program-items">
            {program.controls.map((c) => (
              <article
                key={c.id}
                className="program-item"
                aria-label={c.objective}
              >
                <h4>{c.objective}</h4>
                <p className="preserve">{c.procedure}</p>
                <p className="program-rating">
                  Risques couverts :{" "}
                  {c.riskIds
                    .map(
                      (id) =>
                        program.risks.find((r) => r.id === id)?.title ||
                        "Risque indisponible",
                    )
                    .join(", ")}
                </p>
                {canEdit && (
                  <button
                    className="button secondary"
                    disabled={busy || loading}
                    onClick={() => open({ kind: "controls", value: c })}
                    aria-label={`Modifier le contrôle ${c.objective}`}
                  >
                    Modifier
                  </button>
                )}
              </article>
            ))}
          </div>
          {canEdit && editor && (
            <form
              className="program-editor"
              onSubmit={save}
              key={`${editor.kind}-${editor.value?.id || "new"}`}
            >
              <h3>
                {editor.value ? "Modifier" : "Ajouter"}{" "}
                {editor.kind === "risks" ? "un risque" : "un contrôle"}
              </h3>
              <fieldset disabled={busy || loading}>
                {editor.kind === "risks" ? (
                  <>
                    <label htmlFor="risk-title">Titre du risque</label>
                    <input
                      autoFocus
                      id="risk-title"
                      name="title"
                      required
                      maxLength={200}
                      defaultValue={editor.value?.title}
                      placeholder="Paiement en double"
                    />
                    <label htmlFor="risk-description">
                      Description du risque
                    </label>
                    <textarea
                      id="risk-description"
                      name="description"
                      required
                      maxLength={4000}
                      rows={3}
                      defaultValue={editor.value?.description}
                    />
                    <div className="program-rating-fields">
                      <div>
                        <label htmlFor="risk-probability">Probabilité</label>
                        <select
                          id="risk-probability"
                          name="probability"
                          defaultValue={editor.value?.probability || 3}
                        >
                          {probabilities.map((label, i) => (
                            <option key={label} value={i + 1}>
                              {i + 1} · {label}
                            </option>
                          ))}
                        </select>
                      </div>
                      <div>
                        <label htmlFor="risk-impact">Impact</label>
                        <select
                          id="risk-impact"
                          name="impact"
                          defaultValue={editor.value?.impact || 3}
                        >
                          {impacts.map((label, i) => (
                            <option key={label} value={i + 1}>
                              {i + 1} · {label}
                            </option>
                          ))}
                        </select>
                      </div>
                    </div>
                  </>
                ) : (
                  <>
                    <label htmlFor="control-objective">
                      Objectif du contrôle
                    </label>
                    <input
                      autoFocus
                      id="control-objective"
                      name="objective"
                      required
                      maxLength={1000}
                      defaultValue={editor.value?.objective}
                      placeholder="Vérifier les doublons de factures"
                    />
                    <label htmlFor="control-procedure">
                      Procédure de vérification
                    </label>
                    <textarea
                      id="control-procedure"
                      name="procedure"
                      required
                      maxLength={4000}
                      rows={4}
                      defaultValue={editor.value?.procedure}
                    />
                    <fieldset className="program-risk-choices">
                      <legend>Risques couverts (au moins un)</legend>
                      {program.risks.map((r) => (
                        <label key={r.id}>
                          <input
                            type="checkbox"
                            name="riskIds"
                            value={r.id}
                            defaultChecked={editor.value?.riskIds.includes(
                              r.id,
                            )}
                          />
                          {r.title}
                        </label>
                      ))}
                    </fieldset>
                  </>
                )}
                <div className="program-editor-actions">
                  <button className="button primary" type="submit">
                    {busy ? "Enregistrement…" : "Enregistrer le programme"}
                  </button>
                  <button
                    className="button secondary"
                    type="button"
                    onClick={() => setEditor(null)}
                  >
                    Annuler
                  </button>
                </div>
              </fieldset>
            </form>
          )}
        </>
      )}
    </section>
  );
}
