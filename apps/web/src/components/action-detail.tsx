"use client";
import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { ArrowLeft, ListChecks, Paperclip } from "lucide-react";
import { canManageMission, isAuditeeOnly, isSuperAdmin, type Mission } from "@/lib/types";
import { severityLabels } from "./mission-findings";
import { ErrorNotice, useUser } from "./workspace";
import { ActionEditor, CommentDialog } from "./remediation-dialogs";
import {
  actionReference,
  actionStatusLabels,
  dueLabel,
  followUpStatusLabels,
  readJson,
  remediationHistoryLabels,
  sizeLabel,
  type Action,
  type ActionDetail as Detail,
} from "@/lib/remediation";

type Decision = "COMPLETE" | "VERIFY" | "RETURN" | "CANCEL" | "REOPEN";
const decisions: Record<Decision, { title: string; button: string; done: string }> = {
  COMPLETE: { title: "Déclarer l’action réalisée", button: "Déclarer réalisée", done: "Réalisation déclarée." },
  VERIFY: { title: "Vérifier l’action", button: "Vérifier", done: "Action vérifiée." },
  RETURN: { title: "Renvoyer l’action", button: "Renvoyer", done: "Action renvoyée à son responsable." },
  CANCEL: { title: "Annuler l’action", button: "Annuler l’action", done: "Action annulée." },
  REOPEN: { title: "Rouvrir l’action", button: "Rouvrir", done: "Action rouverte." },
};

export default function ActionDetail({ id }: { id: string }) {
  const user = useUser();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [mission, setMission] = useState<Mission | null>(null);
  const [people, setPeople] = useState<{ subject: string; name: string }[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [decision, setDecision] = useState<Decision | null>(null);
  const [editing, setEditing] = useState(false);
  const [retry, setRetry] = useState(0);
  const base = "/api/remediation/actions/" + id;

  useEffect(() => {
    const abort = new AbortController();
    fetch(base, { cache: "no-store", signal: abort.signal })
      .then((r) => readJson<Detail>(r))
      .then((data) => {
        setDetail(data);
        setError("");
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => abort.abort();
  }, [base, retry]);

  // L'équipe d'audit voit la mission, l'audité reçoit 404 : les droits affichés en découlent.
  const missionId = detail?.action.missionId;
  useEffect(() => {
    if (!missionId || !user || isAuditeeOnly(user)) return;
    const abort = new AbortController();
    fetch("/api/missions/" + missionId, { cache: "no-store", signal: abort.signal })
      .then((r) => (r.ok ? r.json() : null))
      .then(setMission)
      .catch(() => {});
    fetch(`/api/missions/${missionId}/participants`, { cache: "no-store", signal: abort.signal })
      .then((r) => (r.ok ? r.json() : []))
      .then(setPeople)
      .catch(() => {});
    return () => abort.abort();
  }, [missionId, user]);

  // L'audité revient à ses actions, l'équipe d'audit à l'onglet de la mission.
  const back = (
    <Link
      href={!user || isAuditeeOnly(user) || !missionId ? "/actions" : `/missions/${missionId}?tab=remediation`}
      className="back-link"
    >
      <ArrowLeft size={16} /> {!user || isAuditeeOnly(user) || !missionId ? "Mes actions" : "Actions correctives de la mission"}
    </Link>
  );
  if (error && !detail)
    return (
      <>
      {back}
      <section className="panel empty">
        <ListChecks size={32} />
        <h1>Action indisponible</h1>
        <ErrorNotice message={error} />
        <button className="button secondary" onClick={() => setRetry((v) => v + 1)}>
          Réessayer
        </button>
      </section>
      </>
    );
  if (!detail || !user)
    return (
      <div className="loading" role="status">
        Chargement de l’action…
      </div>
    );

  const { action, finding } = detail;
  const reference = actionReference(finding.reference, action.reference);
  const owner = user.subject === action.ownerSubject;
  const manager = !!mission && canManageMission(user, mission);
  const teamAuditor = !!mission && user.roles.includes("AUDITOR") && mission.assigneeSubjects.includes(user.subject);
  const openPlan = finding.status === "ACTION_PLAN";
  const workable = openPlan && (action.status === "TODO" || action.status === "IN_PROGRESS");
  const reviewer = !owner && (manager || teamAuditor || isSuperAdmin(user));
  const name = (subject: string) =>
    subject === user.subject
      ? "Vous"
      : subject === action.ownerSubject
        ? action.ownerName || "Responsable de l’action"
        : people.find((p) => p.subject === subject)?.name || (subject === "audit-service" ? "Synchronisation" : "Équipe d’audit");

  async function send(path: string, init: RequestInit, done: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch(base + path, {
        ...init,
        headers: { ...(init.body instanceof FormData ? {} : { "Content-Type": "application/json" }), "x-csrf-token": user!.csrf },
        signal: AbortSignal.timeout(init.body instanceof FormData ? 30000 : 10000),
      });
      if (!response.ok) await readJson(response);
      // Actions désactivées jusqu'au rechargement des versions.
      setDetail(await readJson<Detail>(await fetch(base, { cache: "no-store" })));
      setNotice(done);
      return true;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Action impossible.");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function transition(kind: Decision, comment: string) {
    const body = JSON.stringify({ version: action.version, action: kind, comment: comment || null });
    if (await send("/transition", { method: "PUT", body }, decisions[kind].done)) setDecision(null);
  }

  async function progress(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const note = String(new FormData(form).get("note") || "").trim();
    if (!note) return;
    if (await send("/progress", { method: "POST", body: JSON.stringify({ version: action.version, note }) }, "Avancement enregistré."))
      form.reset();
  }

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const file = new FormData(form).get("file");
    if (!(file instanceof File) || !file.size) return;
    if (file.size > 10 * 1024 * 1024) {
      setError("Fichier trop volumineux, 10 Mio au plus");
      return;
    }
    const body = new FormData();
    body.set("file", file);
    body.set("version", String(action.version));
    if (await send("/attachments", { method: "POST", body }, "Justificatif déposé.")) form.reset();
  }

  async function removeAttachment(attachmentId: string, fileName: string) {
    if (!confirm(`Retirer le justificatif ${fileName} ?`)) return;
    await send(
      "/attachments/" + attachmentId,
      { method: "DELETE", body: JSON.stringify({ version: action.version }) },
      "Justificatif retiré.",
    );
  }

  async function update(values: { description: string; ownerSubject: string; dueDate: string; reason: string | null }) {
    const body = JSON.stringify({ version: action.version, ...values });
    if (await send("", { method: "PUT", body }, "Action modifiée.")) setEditing(false);
  }

  return (
    <>
      {back}
      <div className="page-heading mission-heading">
        <div>
          <span className="eyebrow">ACTION CORRECTIVE · {reference}</span>
          <h1 className="action-title">{action.description}</h1>
          <p>
            Échéance le {dueLabel(action.dueDate)}
            {action.dueDateChanges > 0 && ` · reportée ${action.dueDateChanges} fois`}
          </p>
        </div>
        <span className={`status large action-status-${action.status.toLowerCase()}`}>
          <span /> {actionStatusLabels[action.status]}
          {action.overdue && " · en retard"}
        </span>
      </div>
      {error && <ErrorNotice message={error} />}
      {notice && <p role="status">{notice}</p>}
      {!openPlan && (
        <p className="program-lock">
          Suivi {followUpStatusLabels[finding.status].toLowerCase()} : l’action est en lecture seule.
        </p>
      )}
      <div className="action-layout">
        <section className="panel detail-main" aria-label="Constat à corriger">
          <div className="section-label">
            <ListChecks size={20} aria-hidden="true" />
            <h2>Constat à corriger</h2>
          </div>
          <p>
            <strong>C-{String(finding.reference).padStart(2, "0")} · {finding.title}</strong>{" "}
            <span className={`status finding-severity-${finding.severity.toLowerCase()}`}>
              Gravité {severityLabels[finding.severity].toLowerCase()}
            </span>
          </p>
          <dl className="finding-fields">
            <dt>Condition observée</dt>
            <dd className="preserve">{finding.conditionText}</dd>
            {finding.cause && (
              <>
                <dt>Cause</dt>
                <dd className="preserve">{finding.cause}</dd>
              </>
            )}
            {finding.recommendation && (
              <>
                <dt>Recommandation</dt>
                <dd className="preserve">{finding.recommendation}</dd>
              </>
            )}
          </dl>
          <dl className="mission-dates">
            <div>
              <dt>Responsable</dt>
              <dd>
                {action.ownerName || "Compte inconnu"}
                {action.ownerActive === false && " (compte désactivé)"}
              </dd>
            </div>
            <div>
              <dt>Justificatifs</dt>
              <dd>{action.attachmentCount}</dd>
            </div>
          </dl>
          {action.completionNote && (
            <div className="mission-decision">
              <h3>Déclaration de réalisation</h3>
              <p className="preserve">{action.completionNote}</p>
            </div>
          )}
          {action.verificationComment && (
            <div className={`mission-decision ${action.status === "VERIFIED" ? "approved" : "corrections"}`}>
              <h3>{action.status === "VERIFIED" ? "Commentaire de vérification" : "Dernier renvoi"}</h3>
              <p className="preserve">{action.verificationComment}</p>
            </div>
          )}
          {action.cancelReason && (
            <div className="mission-decision">
              <h3>Motif de l’annulation</h3>
              <p className="preserve">{action.cancelReason}</p>
            </div>
          )}
          <div className="row-buttons">
            {owner && workable && (
              <button
                className="button primary compact"
                disabled={busy || detail.attachments.length === 0}
                title={detail.attachments.length === 0 ? "Déposez d’abord un justificatif" : undefined}
                onClick={() => setDecision("COMPLETE")}
              >
                Déclarer réalisée
              </button>
            )}
            {reviewer && openPlan && action.status === "DONE" && (
              <>
                <button className="button primary compact" disabled={busy} onClick={() => setDecision("VERIFY")}>
                  Vérifier
                </button>
                <button className="button secondary compact" disabled={busy} onClick={() => setDecision("RETURN")}>
                  Renvoyer
                </button>
              </>
            )}
            {manager && workable && (
              <button className="button secondary compact" disabled={busy} onClick={() => setEditing(true)}>
                Modifier
              </button>
            )}
            {manager && openPlan && ["TODO", "IN_PROGRESS", "DONE"].includes(action.status) && (
              <button className="button secondary compact" disabled={busy} onClick={() => setDecision("CANCEL")}>
                Annuler l’action
              </button>
            )}
            {manager && openPlan && action.status === "VERIFIED" && (
              <button className="button secondary compact" disabled={busy} onClick={() => setDecision("REOPEN")}>
                Rouvrir
              </button>
            )}
          </div>
          {owner && workable && detail.attachments.length === 0 && (
            <p className="program-rating">Un justificatif au moins est exigé avant de déclarer l’action réalisée.</p>
          )}
        </section>
        <section className="panel detail-main" aria-label="Justificatifs">
          <div className="section-label">
            <Paperclip size={20} aria-hidden="true" />
            <h2>Justificatifs</h2>
          </div>
          {!detail.attachments.length && <p className="panel-message">Aucun justificatif déposé.</p>}
          <ul className="attachment-list">
            {detail.attachments.map((a) => (
              <li key={a.id}>
                <a href={`${base}/attachments/${a.id}`} download={a.fileName}>
                  {a.fileName}
                </a>
                <small>
                  {sizeLabel(a.sizeBytes)} · déposé par {name(a.uploadedBy)} le{" "}
                  {new Date(a.uploadedAt).toLocaleString("fr-FR")}
                </small>
                {owner && workable && a.uploadedBy === user.subject && (
                  <button
                    className="button secondary compact"
                    disabled={busy}
                    onClick={() => removeAttachment(a.id, a.fileName)}
                    aria-label={`Retirer ${a.fileName}`}
                  >
                    Retirer
                  </button>
                )}
              </li>
            ))}
          </ul>
          {owner && workable && detail.attachments.length < 10 && (
            <form className="attachment-form" onSubmit={upload}>
              <label htmlFor="attachment-file">Ajouter un justificatif (PDF, JPEG ou PNG, 10 Mio au plus)</label>
              <input id="attachment-file" name="file" type="file" accept="application/pdf,image/jpeg,image/png" required />
              <button className="button secondary" disabled={busy}>
                {busy ? "Envoi…" : "Déposer"}
              </button>
            </form>
          )}
        </section>
        <section className="panel detail-main" aria-label="Avancement et historique">
          <div className="section-label">
            <h2>Avancement</h2>
          </div>
          {owner && workable && (
            <form className="program-editor" onSubmit={progress}>
              <label htmlFor="progress-note">Nouvel avancement</label>
              <textarea id="progress-note" name="note" maxLength={2000} rows={3} required />
              <button className="button secondary" disabled={busy}>
                Enregistrer l’avancement
              </button>
            </form>
          )}
          {!detail.progress.length && <p className="panel-message">Aucun avancement saisi.</p>}
          <ol className="finding-history" aria-label="Avancements">
            {detail.progress.map((p) => (
              <li key={p.id}>
                <strong>{name(p.authorSubject)}</strong> · {new Date(p.createdAt).toLocaleString("fr-FR")}
                <p className="preserve">{p.note}</p>
              </li>
            ))}
          </ol>
          <h3>Historique</h3>
          <ol className="finding-history" aria-label="Historique de l’action">
            {detail.history.map((h) => (
              <li key={h.id}>
                <strong>{remediationHistoryLabels[h.action] || h.action}</strong> par {name(h.actorSubject)} le{" "}
                {new Date(h.occurredAt).toLocaleString("fr-FR")}
                {h.override && " (dérogation du superadministrateur)"}
                {h.reason && <p className="preserve">{h.reason}</p>}
              </li>
            ))}
          </ol>
        </section>
      </div>
      {decision && (
        <CommentDialog
          title={`${decisions[decision].title} ${reference}`}
          subtitle={action.description}
          button={decisions[decision].button}
          required={decision !== "VERIFY"}
          busy={busy}
          error={error}
          onSubmit={(comment) => transition(decision, comment)}
          onClose={() => setDecision(null)}
        />
      )}
      {editing && (
        <ActionEditor
          action={action as Action}
          busy={busy}
          error={error}
          onSubmit={update}
          onClose={() => setEditing(false)}
        />
      )}
    </>
  );
}
