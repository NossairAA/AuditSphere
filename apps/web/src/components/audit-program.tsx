"use client";
import ControlEvidence from "./control-evidence";
import ControlResultForm, { type ControlResult } from "./control-result";
import Link from "next/link";
import { useUnsavedGuard } from "./unsaved-guard";
import { useEffect, useState, useRef, type FormEvent } from "react";
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
  result: ControlResult | null;
};
type Program = { mission: Mission; risks: Risk[]; controls: Control[] };
type Editor =
  | { kind: "risks"; value?: Risk }
  | { kind: "controls"; value?: Control; riskId?: string };
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
  mode = "risks",
  controlId,
  returnTo = "/missions",
  onUpdated,
}: {
  mission: Mission;
  mode?: "risks" | "controls";
  controlId?: string;
  returnTo?: string;
  onUpdated: (mission: Mission) => void;
}) {
  const user = useUser();
  const [search, setSearch] = useState("");
  const [probability, setProbability] = useState("");
  const [impact, setImpact] = useState("");
  const [uncovered, setUncovered] = useState(false);
  const [riskFilter, setRiskFilter] = useState("");
  const [outcome, setOutcome] = useState("");
  const [dirty, setDirty] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  useUnsavedGuard(dirty);
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
  useEffect(() => {
    if (editor) dialog.current?.showModal();
    else dialog.current?.close();
  }, [editor]);
  function closeEditor() {
    if (!dirty || window.confirm("Abandonner les modifications ?")) {
      setDirty(false);
      setEditor(null);
    }
  }
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
      setDirty(false);
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
  const riskItems =
    program?.risks.filter(
      (r) =>
        r.title
          .toLocaleLowerCase("fr")
          .includes(search.toLocaleLowerCase("fr")) &&
        (!probability || r.probability === Number(probability)) &&
        (!impact || r.impact === Number(impact)) &&
        (!uncovered || !program.controls.some((c) => c.riskIds.includes(r.id))),
    ) || [];
  const controlItems =
    program?.controls.filter(
      (c) =>
        c.objective
          .toLocaleLowerCase("fr")
          .includes(search.toLocaleLowerCase("fr")) &&
        (!riskFilter || c.riskIds.includes(riskFilter)) &&
        (!outcome || (c.result?.outcome || "TODO") === outcome),
    ) || [];
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
          {!controlId && (
            <div className="program-tools">
              <div className="program-section-heading">
                <h3>
                  {mode === "risks" ? "Risques" : "Contrôles"} (
                  {mode === "risks"
                    ? program.risks.length
                    : program.controls.length}
                  )
                </h3>
                {canEdit && (
                  <button
                    className="button primary"
                    disabled={
                      busy ||
                      loading ||
                      (mode === "controls" && !program.risks.length) ||
                      (mode === "risks"
                        ? program.risks.length
                        : program.controls.length) >= 100
                    }
                    onClick={() => open({ kind: mode })}
                  >
                    {mode === "risks"
                      ? "Ajouter un risque"
                      : "Ajouter un contrôle"}
                  </button>
                )}
              </div>
              <div className="directory-filters">
                <label>
                  Rechercher
                  <input
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    placeholder={
                      mode === "risks"
                        ? "Titre du risque"
                        : "Objectif du contrôle"
                    }
                  />
                </label>
                {mode === "risks" ? (
                  <>
                    <label>
                      Probabilité
                      <select
                        value={probability}
                        onChange={(e) => setProbability(e.target.value)}
                      >
                        <option value="">Toutes</option>
                        {probabilities.map((p, i) => (
                          <option key={p} value={i + 1}>
                            {i + 1} · {p}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Impact
                      <select
                        value={impact}
                        onChange={(e) => setImpact(e.target.value)}
                      >
                        <option value="">Tous</option>
                        {impacts.map((p, i) => (
                          <option key={p} value={i + 1}>
                            {i + 1} · {p}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="checkbox-label">
                      <input
                        type="checkbox"
                        checked={uncovered}
                        onChange={(e) => setUncovered(e.target.checked)}
                      />
                      Sans contrôle
                    </label>
                  </>
                ) : (
                  <>
                    <label>
                      Risque
                      <select
                        value={riskFilter}
                        onChange={(e) => setRiskFilter(e.target.value)}
                      >
                        <option value="">Tous les risques</option>
                        {program.risks.map((r) => (
                          <option key={r.id} value={r.id}>
                            {r.title}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Résultat
                      <select
                        value={outcome}
                        onChange={(e) => setOutcome(e.target.value)}
                      >
                        <option value="">Tous</option>
                        <option value="TODO">À réaliser</option>
                        <option value="CONFORMING">Conforme</option>
                        <option value="NON_CONFORMING">Non conforme</option>
                        <option value="NOT_APPLICABLE">Non applicable</option>
                      </select>
                    </label>
                  </>
                )}
              </div>
            </div>
          )}
          {mode === "risks" && !controlId && (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Risque</th>
                    <th>Probabilité</th>
                    <th>Impact</th>
                    <th>Contrôles</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {riskItems.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <details>
                          <summary>
                            <strong>{r.title}</strong>
                          </summary>
                          <p className="preserve">{r.description}</p>
                        </details>
                      </td>
                      <td>
                        {r.probability}/5 · {probabilities[r.probability - 1]}
                      </td>
                      <td>
                        {r.impact}/5 · {impacts[r.impact - 1]}
                      </td>
                      <td>
                        {
                          program.controls.filter((c) =>
                            c.riskIds.includes(r.id),
                          ).length
                        }
                      </td>
                      <td>
                        {canEdit && (
                          <div className="row-buttons">
                            <button
                              className="button secondary compact"
                              onClick={() => open({ kind: "risks", value: r })}
                              aria-label={`Modifier le risque ${r.title}`}
                            >
                              Modifier
                            </button>
                            <button
                              className="button secondary compact"
                              disabled={program.controls.length >= 100}
                              onClick={() =>
                                open({ kind: "controls", riskId: r.id })
                              }
                            >
                              Ajouter un contrôle
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {program.risks.length > 0 && !riskItems.length && (
                <p className="panel-message">
                  Aucun risque ne correspond aux filtres.
                </p>
              )}
              {!program.risks.length && (
                <p className="panel-message">
                  Ajoutez un premier risque pour préparer les contrôles.
                </p>
              )}
            </div>
          )}
          {mode === "controls" && !controlId && (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Objectif</th>
                    <th>Risques couverts</th>
                    <th>Résultat</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {controlItems.map((c) => (
                    <tr key={c.id}>
                      <td>
                        <strong>{c.objective}</strong>
                      </td>
                      <td>
                        {c.riskIds
                          .map(
                            (id) =>
                              program.risks.find((r) => r.id === id)?.title,
                          )
                          .join(", ")}
                      </td>
                      <td>
                        {c.result
                          ? {
                              CONFORMING: "Conforme",
                              NON_CONFORMING: "Non conforme",
                              NOT_APPLICABLE: "Non applicable",
                            }[c.result.outcome]
                          : "À réaliser"}
                      </td>
                      <td>
                        <Link
                          className="button secondary compact"
                          href={`/missions/${mission.id}/controls/${c.id}?returnTo=${encodeURIComponent(returnTo)}`}
                        >
                          Ouvrir le contrôle
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {program.controls.length > 0 && !controlItems.length && (
                <p className="panel-message">
                  Aucun contrôle ne correspond aux filtres.
                </p>
              )}
              {!program.controls.length && (
                <p className="panel-message">
                  {program.risks.length
                    ? "Ajoutez votre premier contrôle."
                    : "Commencez par ajouter un risque dans l’onglet Risques."}
                </p>
              )}
            </div>
          )}
          {controlId &&
            (() => {
              const c = program.controls.find((c) => c.id === controlId);
              return c ? (
                <article className="control-detail" aria-label={c.objective}>
                  <h2>{c.objective}</h2>
                  <h3>Procédure de vérification</h3>
                  <p className="preserve">{c.procedure}</p>
                  <h3>Risques couverts</h3>
                  <ul>
                    {c.riskIds.map((id) => (
                      <li key={id}>
                        {program.risks.find((r) => r.id === id)?.title}
                      </li>
                    ))}
                  </ul>
                  {canEdit && (
                    <button
                      className="button secondary"
                      onClick={() => open({ kind: "controls", value: c })}
                    >
                      Modifier le contrôle
                    </button>
                  )}
                  <ControlResultForm
                    mission={program.mission}
                    controlId={c.id}
                    result={c.result}
                    onSaved={reload}
                  />
                  <ControlEvidence
                    mission={program.mission}
                    controlId={c.id}
                    onSaved={reload}
                  />
                </article>
              ) : (
                <ErrorNotice message="Contrôle introuvable dans cette mission." />
              );
            })()}
          {canEdit && editor && (
            <dialog
              ref={dialog}
              className="editor-drawer"
              aria-labelledby="program-editor-title"
              onCancel={(e) => {
                e.preventDefault();
                closeEditor();
              }}
            >
              <form
                onChange={() => setDirty(true)}
                className="program-editor"
                onSubmit={save}
                key={`${editor.kind}-${editor.value?.id || "new"}`}
              >
                <h3 id="program-editor-title">
                  {editor.value ? "Modifier" : "Ajouter"}{" "}
                  {editor.kind === "risks" ? "un risque" : "un contrôle"}
                </h3>
                {editor.kind === "controls" && editor.value?.result && (
                  <p role="note">
                    Modifier ce contrôle effacera son résultat courant. Il devra
                    être exécuté à nouveau. Le résultat précédent restera tracé.
                  </p>
                )}
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
                              defaultChecked={
                                editor.value?.riskIds.includes(r.id) ||
                                editor.riskId === r.id
                              }
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
                      onClick={closeEditor}
                    >
                      Annuler
                    </button>
                  </div>
                </fieldset>
              </form>
            </dialog>
          )}
        </>
      )}
    </section>
  );
}
