"use client";
import { useUnsavedGuard } from "./unsaved-guard";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { FileWarning } from "lucide-react";
import { isSuperAdmin, type Mission } from "@/lib/types";
import { ErrorNotice, useUser } from "./workspace";

type Severity = "LOW" | "MODERATE" | "HIGH" | "CRITICAL";
type Status = "DRAFT" | "SUBMITTED" | "VALIDATED" | "REJECTED";
type Outcome = "CONFORMING" | "NON_CONFORMING" | "NOT_APPLICABLE";
type Finding = {
  id: string;
  reference: number;
  title: string;
  condition: string;
  criterion: string | null;
  cause: string | null;
  consequence: string | null;
  recommendation: string | null;
  severity: Severity;
  status: Status;
  version: number;
  everSubmitted: boolean;
  createdBy: string;
  updatedBy: string;
  updatedAt: string;
  submittedBy: string | null;
  submittedAt: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionComment: string | null;
  controls: { id: string; objective: string; outcome: Outcome | null }[];
  riskIds: string[];
  inconsistentControls: boolean;
};
type ProgramControl = {
  id: string;
  objective: string;
  result: { outcome: Outcome } | null;
};
type HistoryEntry = {
  action: string;
  actorSubject: string;
  reason: string | null;
  override: boolean;
  occurredAt: string;
};
type Decision = "VALIDATE" | "RETURN" | "REJECT" | "REOPEN";

export const severityLabels: Record<Severity, string> = {
  LOW: "Faible",
  MODERATE: "Modérée",
  HIGH: "Élevée",
  CRITICAL: "Critique",
};
const statusLabels: Record<Status, string> = {
  DRAFT: "Brouillon",
  SUBMITTED: "Soumis",
  VALIDATED: "Validé",
  REJECTED: "Rejeté",
};
const outcomeLabels: Record<Outcome, string> = {
  CONFORMING: "Conforme",
  NON_CONFORMING: "Non conforme",
  NOT_APPLICABLE: "Non applicable",
};
const historyLabels: Record<string, string> = {
  CREATED: "Création",
  UPDATED: "Modification",
  SUBMITTED: "Soumission",
  WITHDRAWN: "Retrait de la soumission",
  VALIDATED: "Validation",
  RETURNED: "Renvoi en brouillon",
  REJECTED: "Rejet",
  REOPENED: "Réouverture",
  DELETED: "Suppression",
};
const decisionLabels: Record<Decision, { title: string; button: string }> = {
  VALIDATE: { title: "Valider le constat", button: "Valider" },
  RETURN: { title: "Renvoyer en brouillon", button: "Renvoyer" },
  REJECT: { title: "Rejeter le constat", button: "Rejeter" },
  REOPEN: { title: "Rouvrir le constat", button: "Rouvrir" },
};
export const findingReference = (reference: number) =>
  "C-" + String(reference).padStart(2, "0");

export default function MissionFindings({ mission }: { mission: Mission }) {
  const user = useUser();
  const [findings, setFindings] = useState<Finding[] | null>(null);
  const [controls, setControls] = useState<ProgramControl[]>([]);
  const [people, setPeople] = useState<{ subject: string; name: string }[]>([]);
  const [history, setHistory] = useState<Record<string, HistoryEntry[]>>({});
  const [editor, setEditor] = useState<{ value?: Finding } | null>(null);
  const [decision, setDecision] = useState<{
    finding: Finding;
    action: Decision;
  } | null>(null);
  const [status, setStatus] = useState("");
  const [severity, setSeverity] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [retry, setRetry] = useState(0);
  const drawer = useRef<HTMLDialogElement>(null);
  const decisionDialog = useRef<HTMLDialogElement>(null);
  useUnsavedGuard(dirty);
  const open = mission.status === "IN_PROGRESS";
  const writer =
    isSuperAdmin(user) ||
    (!!user &&
      user.roles.includes("AUDITOR") &&
      mission.assigneeSubjects.includes(user.subject));
  const decider =
    isSuperAdmin(user) ||
    (!!user &&
      user.roles.includes("AUDIT_MANAGER") &&
      user.subject === mission.ownerSubject);

  useEffect(() => {
    const abort = new AbortController();
    const load = (path: string) =>
      fetch(`/api/missions/${mission.id}/${path}`, {
        cache: "no-store",
        signal: abort.signal,
      }).then(async (r) => {
        const data = await r.json();
        if (!r.ok) throw new Error(data.error);
        return data;
      });
    Promise.all([load("findings"), load("program")])
      .then(([list, program]) => {
        setFindings(list);
        setControls(program.controls);
        setError("");
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    load("participants")
      .then(setPeople)
      .catch(() => {});
    return () => abort.abort();
  }, [mission.id, mission.version, retry]);
  useEffect(() => {
    if (editor) drawer.current?.showModal();
    else drawer.current?.close();
  }, [editor]);
  useEffect(() => {
    if (decision) decisionDialog.current?.showModal();
    else decisionDialog.current?.close();
  }, [decision]);

  const name = (subject: string | null) =>
    !subject
      ? ""
      : people.find((p) => p.subject === subject)?.name ||
        (subject === user?.subject ? user.name : "Compte hors équipe");

  async function send(
    path: string,
    method: "POST" | "PUT" | "DELETE",
    body: object,
  ) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch(`/api/missions/${mission.id}/findings${path}`, {
        method,
        headers: {
          "Content-Type": "application/json",
          "x-csrf-token": user!.csrf,
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(10000),
      });
      if (!response.ok) throw new Error((await response.json()).error);
      // Keep actions disabled until fresh versions replace the stale list.
      const reload = await fetch(`/api/missions/${mission.id}/findings`, {
        cache: "no-store",
        signal: AbortSignal.timeout(10000),
      });
      const list = await reload.json();
      if (!reload.ok) throw new Error(list.error);
      setFindings(list);
      setHistory({});
      return true;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Action impossible.");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editor) return;
    const values = new FormData(event.currentTarget);
    const text = (key: string) => String(values.get(key) || "").trim() || null;
    const ok = await send(
      editor.value ? "/" + editor.value.id : "",
      editor.value ? "PUT" : "POST",
      {
        version: editor.value?.version,
        title: text("title"),
        condition: text("condition"),
        criterion: text("criterion"),
        cause: text("cause"),
        consequence: text("consequence"),
        recommendation: text("recommendation"),
        severity: values.get("severity"),
        controlIds: values.getAll("controlIds"),
      },
    );
    if (ok) {
      setDirty(false);
      setEditor(null);
      setNotice("Constat enregistré.");
    }
  }

  async function transition(
    finding: Finding,
    action: "SUBMIT" | "WITHDRAW" | Decision,
    comment?: string,
  ) {
    const ok = await send(`/${finding.id}/transition`, "PUT", {
      version: finding.version,
      action,
      comment: comment || null,
    });
    if (ok) {
      setDecision(null);
      setNotice(
        {
          SUBMIT: "Constat soumis au responsable.",
          WITHDRAW: "Soumission retirée.",
          VALIDATE: "Constat validé.",
          RETURN: "Constat renvoyé en brouillon.",
          REJECT: "Constat rejeté.",
          REOPEN: "Constat rouvert.",
        }[action],
      );
    }
  }

  async function remove(finding: Finding) {
    if (!confirm(`Supprimer le brouillon ${findingReference(finding.reference)} ?`))
      return;
    if (await send("/" + finding.id, "DELETE", { version: finding.version }))
      setNotice("Brouillon supprimé.");
  }

  async function toggleHistory(finding: Finding) {
    if (history[finding.id]) {
      const next = { ...history };
      delete next[finding.id];
      setHistory(next);
      return;
    }
    try {
      const r = await fetch(
        `/api/missions/${mission.id}/findings/${finding.id}/history`,
        { cache: "no-store", signal: AbortSignal.timeout(10000) },
      );
      const data = await r.json();
      if (!r.ok) throw new Error(data.error);
      setHistory({ ...history, [finding.id]: data });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Historique indisponible.");
    }
  }

  function closeEditor() {
    if (!dirty || confirm("Abandonner les modifications ?")) {
      setDirty(false);
      setEditor(null);
    }
  }

  const items =
    findings?.filter(
      (f) =>
        (!status || f.status === status) && (!severity || f.severity === severity),
    ) || [];
  const pending =
    findings?.filter((f) => f.status === "DRAFT" || f.status === "SUBMITTED")
      .length || 0;

  return (
    <section
      className="panel mission-program mission-findings"
      id="mission-constats"
      tabIndex={-1}
      aria-label="Constats"
    >
      <div className="section-label">
        <FileWarning size={20} aria-hidden="true" />
        <h2>Constats</h2>
      </div>
      <p>
        Transformez les écarts relevés par les contrôles en constats argumentés,
        relus par le responsable avant la validation de la mission.
      </p>
      {!open && (
        <p className="program-lock">
          {mission.status === "DRAFT"
            ? "Les constats seront disponibles après le démarrage de la mission."
            : "Constats en lecture seule pendant la validation et après approbation."}
        </p>
      )}
      {open && pending > 0 && (
        <p className="notice" role="note">
          {pending === 1
            ? "Un constat attend encore une décision."
            : `${pending} constats attendent encore une décision.`}{" "}
          La mission ne pourra être soumise au validateur qu’une fois chaque
          constat validé ou rejeté.
        </p>
      )}
      {error && <ErrorNotice message={error} />}
      {error && (
        <button
          className="button secondary"
          disabled={busy}
          onClick={() => {
            setError("");
            setRetry((v) => v + 1);
          }}
        >
          Actualiser les constats
        </button>
      )}
      {notice && <p role="status">{notice}</p>}
      {!findings && !error && <p role="status">Chargement des constats…</p>}
      {findings && (
        <>
          <div className="program-tools">
            <div className="program-section-heading">
              <h3>Constats ({findings.length})</h3>
              {open && writer && (
                <button
                  className="button primary"
                  disabled={busy || !controls.length || findings.length >= 100}
                  onClick={() => {
                    setNotice("");
                    setEditor({});
                  }}
                >
                  Nouveau constat
                </button>
              )}
            </div>
            {open && writer && !controls.length && (
              <p className="panel-message">
                Ajoutez d’abord des contrôles au programme d’audit.
              </p>
            )}
            <div className="directory-filters">
              <label>
                Statut
                <select value={status} onChange={(e) => setStatus(e.target.value)}>
                  <option value="">Tous</option>
                  {Object.entries(statusLabels).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Gravité
                <select
                  value={severity}
                  onChange={(e) => setSeverity(e.target.value)}
                >
                  <option value="">Toutes</option>
                  {Object.entries(severityLabels).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          </div>
          {!findings.length && (
            <p className="panel-message">Aucun constat pour cette mission.</p>
          )}
          {findings.length > 0 && !items.length && (
            <p className="panel-message">
              Aucun constat ne correspond aux filtres.
            </p>
          )}
          <ul className="finding-list">
            {items.map((f) => {
              const reference = findingReference(f.reference);
              const ownSubmission = f.submittedBy === user?.subject;
              return (
                <li key={f.id}>
                  <article className="finding-card" aria-label={`${reference} ${f.title}`}>
                    <header>
                      <span className="finding-reference">{reference}</span>
                      <h3>{f.title}</h3>
                      <span className={`status finding-severity-${f.severity.toLowerCase()}`}>
                        Gravité {severityLabels[f.severity].toLowerCase()}
                      </span>
                      <span className={`status finding-status-${f.status.toLowerCase()}`}>
                        <span /> {statusLabels[f.status]}
                      </span>
                    </header>
                    {f.inconsistentControls && f.status !== "REJECTED" && (
                      <p className="notice" role="note">
                        Un contrôle lié n’a pas le résultat Non conforme. Le
                        constat ne peut être soumis qu’avec au moins un contrôle
                        Non conforme, et un contrôle modifié doit être réexécuté.
                      </p>
                    )}
                    {f.decisionComment && (
                      <div className="mission-decision">
                        <h4>
                          {f.status === "REJECTED"
                            ? "Motif du rejet"
                            : f.status === "VALIDATED"
                              ? "Commentaire de validation"
                              : "Dernier retour du responsable"}
                        </h4>
                        <p className="preserve">{f.decisionComment}</p>
                      </div>
                    )}
                    <dl className="finding-fields">
                      <dt>Condition observée</dt>
                      <dd className="preserve">{f.condition}</dd>
                      {f.criterion && (
                        <>
                          <dt>Critère attendu</dt>
                          <dd className="preserve">{f.criterion}</dd>
                        </>
                      )}
                      <dt>Cause</dt>
                      <dd className="preserve">{f.cause || "À compléter avant soumission"}</dd>
                      {f.consequence && (
                        <>
                          <dt>Conséquence</dt>
                          <dd className="preserve">{f.consequence}</dd>
                        </>
                      )}
                      <dt>Recommandation</dt>
                      <dd className="preserve">
                        {f.recommendation || "À compléter avant soumission"}
                      </dd>
                      <dt>Contrôles liés</dt>
                      <dd>
                        <ul>
                          {f.controls.map((c) => (
                            <li key={c.id}>
                              {c.objective} ·{" "}
                              {c.outcome ? outcomeLabels[c.outcome] : "À réaliser"}
                            </li>
                          ))}
                        </ul>
                      </dd>
                    </dl>
                    <p className="program-rating">
                      Créé par {name(f.createdBy)}
                      {f.submittedBy && f.status !== "DRAFT" && (
                        <>, soumis par {name(f.submittedBy)}</>
                      )}
                      {f.decidedBy && (
                        <>, dernière décision de {name(f.decidedBy)}</>
                      )}
                      . Mis à jour le{" "}
                      {new Date(f.updatedAt).toLocaleString("fr-FR")}.
                    </p>
                    <div className="row-buttons">
                      {open && writer && f.status === "DRAFT" && (
                        <>
                          <button
                            className="button secondary compact"
                            disabled={busy}
                            onClick={() => {
                              setNotice("");
                              setEditor({ value: f });
                            }}
                            aria-label={`Modifier ${reference}`}
                          >
                            Modifier
                          </button>
                          <button
                            className="button primary compact"
                            disabled={busy}
                            onClick={() => transition(f, "SUBMIT")}
                            aria-label={`Soumettre ${reference}`}
                          >
                            Soumettre
                          </button>
                          {!f.everSubmitted && (
                            <button
                              className="button secondary compact"
                              disabled={busy}
                              onClick={() => remove(f)}
                              aria-label={`Supprimer ${reference}`}
                            >
                              Supprimer
                            </button>
                          )}
                        </>
                      )}
                      {open && writer && f.status === "SUBMITTED" && (
                        <button
                          className="button secondary compact"
                          disabled={busy}
                          onClick={() => transition(f, "WITHDRAW")}
                          aria-label={`Retirer la soumission de ${reference}`}
                        >
                          Retirer la soumission
                        </button>
                      )}
                      {open && decider && f.status === "SUBMITTED" &&
                        (ownSubmission ? (
                          <p className="program-rating">
                            Vous avez soumis ce constat : un autre responsable
                            doit décider.
                          </p>
                        ) : (
                          (["VALIDATE", "RETURN", "REJECT"] as const).map((action) => (
                            <button
                              key={action}
                              className={`button ${action === "VALIDATE" ? "primary" : "secondary"} compact`}
                              disabled={busy}
                              onClick={() => setDecision({ finding: f, action })}
                              aria-label={`${decisionLabels[action].button} ${reference}`}
                            >
                              {decisionLabels[action].button}
                            </button>
                          ))
                        ))}
                      {open && decider && f.status === "VALIDATED" && (
                        <button
                          className="button secondary compact"
                          disabled={busy}
                          onClick={() => setDecision({ finding: f, action: "REOPEN" })}
                          aria-label={`Rouvrir ${reference}`}
                        >
                          Rouvrir
                        </button>
                      )}
                      <button
                        className="button secondary compact"
                        onClick={() => toggleHistory(f)}
                        aria-expanded={!!history[f.id]}
                        aria-label={`Historique de ${reference}`}
                      >
                        Historique
                      </button>
                    </div>
                    {history[f.id] && (
                      <ol className="finding-history" aria-label={`Historique de ${reference}`}>
                        {history[f.id].map((h, i) => (
                          <li key={i}>
                            <strong>{historyLabels[h.action] || h.action}</strong>{" "}
                            par {name(h.actorSubject)}
                            {h.override && " (dérogation)"}, le{" "}
                            {new Date(h.occurredAt).toLocaleString("fr-FR")}
                            {h.reason && <p className="preserve">{h.reason}</p>}
                          </li>
                        ))}
                      </ol>
                    )}
                  </article>
                </li>
              );
            })}
          </ul>
        </>
      )}
      {editor && (
        <dialog
          ref={drawer}
          className="editor-drawer"
          aria-labelledby="finding-editor-title"
          onCancel={(e) => {
            e.preventDefault();
            closeEditor();
          }}
        >
          <form
            className="program-editor"
            onChange={() => setDirty(true)}
            onSubmit={save}
            key={editor.value?.id || "new"}
          >
            <h3 id="finding-editor-title">
              {editor.value
                ? `Modifier ${findingReference(editor.value.reference)}`
                : "Nouveau constat"}
            </h3>
            <p>
              La cause et la recommandation peuvent attendre le brouillon, elles
              sont exigées pour soumettre.
            </p>
            {error && <ErrorNotice message={error} />}
            <fieldset disabled={busy}>
              <label htmlFor="finding-title">Titre du constat</label>
              <input
                autoFocus
                id="finding-title"
                name="title"
                required
                maxLength={200}
                defaultValue={editor.value?.title}
              />
              <label htmlFor="finding-condition">Condition observée</label>
              <textarea
                id="finding-condition"
                name="condition"
                required
                maxLength={4000}
                rows={3}
                defaultValue={editor.value?.condition}
              />
              <label htmlFor="finding-severity">Gravité</label>
              <select
                id="finding-severity"
                name="severity"
                required
                defaultValue={editor.value?.severity || ""}
              >
                <option value="" disabled>
                  Choisir une gravité
                </option>
                {Object.entries(severityLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
              <label htmlFor="finding-criterion">Critère attendu (facultatif)</label>
              <textarea
                id="finding-criterion"
                name="criterion"
                maxLength={4000}
                rows={2}
                defaultValue={editor.value?.criterion || ""}
              />
              <label htmlFor="finding-cause">Cause</label>
              <textarea
                id="finding-cause"
                name="cause"
                maxLength={4000}
                rows={2}
                defaultValue={editor.value?.cause || ""}
              />
              <label htmlFor="finding-consequence">Conséquence (facultatif)</label>
              <textarea
                id="finding-consequence"
                name="consequence"
                maxLength={4000}
                rows={2}
                defaultValue={editor.value?.consequence || ""}
              />
              <label htmlFor="finding-recommendation">Recommandation</label>
              <textarea
                id="finding-recommendation"
                name="recommendation"
                maxLength={4000}
                rows={3}
                defaultValue={editor.value?.recommendation || ""}
              />
              <fieldset className="program-risk-choices">
                <legend>Contrôles liés (au moins un, 20 au plus)</legend>
                {controls.map((c) => (
                  <label key={c.id}>
                    <input
                      type="checkbox"
                      name="controlIds"
                      value={c.id}
                      defaultChecked={editor.value?.controls.some(
                        (linked) => linked.id === c.id,
                      )}
                    />
                    {c.objective} ·{" "}
                    {c.result ? outcomeLabels[c.result.outcome] : "À réaliser"}
                  </label>
                ))}
              </fieldset>
              <div className="program-editor-actions">
                <button className="button primary" type="submit">
                  {busy ? "Enregistrement…" : "Enregistrer le constat"}
                </button>
                <button className="button secondary" type="button" onClick={closeEditor}>
                  Annuler
                </button>
              </div>
            </fieldset>
          </form>
        </dialog>
      )}
      {decision && (
        <dialog
          ref={decisionDialog}
          className="editor-drawer"
          aria-labelledby="finding-decision-title"
          onCancel={(e) => {
            e.preventDefault();
            setDecision(null);
          }}
        >
          <form
            className="program-editor"
            onSubmit={(event) => {
              event.preventDefault();
              const comment = String(
                new FormData(event.currentTarget).get("comment") || "",
              ).trim();
              transition(decision.finding, decision.action, comment);
            }}
          >
            <h3 id="finding-decision-title">
              {decisionLabels[decision.action].title}{" "}
              {findingReference(decision.finding.reference)}
            </h3>
            <p>{decision.finding.title}</p>
            {error && <ErrorNotice message={error} />}
            <fieldset disabled={busy}>
              <label htmlFor="finding-decision-comment">
                {decision.action === "VALIDATE"
                  ? "Commentaire (facultatif)"
                  : "Commentaire obligatoire"}
              </label>
              <textarea
                autoFocus
                id="finding-decision-comment"
                name="comment"
                maxLength={2000}
                rows={4}
                required={decision.action !== "VALIDATE"}
              />
              <div className="program-editor-actions">
                <button className="button primary" type="submit">
                  {busy ? "Enregistrement…" : decisionLabels[decision.action].button}
                </button>
                <button
                  className="button secondary"
                  type="button"
                  onClick={() => setDecision(null)}
                >
                  Annuler
                </button>
              </div>
            </fieldset>
          </form>
        </dialog>
      )}
    </section>
  );
}
