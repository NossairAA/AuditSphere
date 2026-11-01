"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { ListChecks } from "lucide-react";
import { canManageMission, isSuperAdmin, type Mission } from "@/lib/types";
import { findingReference, severityLabels } from "./mission-findings";
import { ErrorNotice, useUser } from "./workspace";
import { ActionEditor, CommentDialog } from "./remediation-dialogs";
import {
  actionStatusLabels,
  dueLabel,
  followUpStatusLabels,
  readJson,
  remediationHistoryLabels,
  type FollowUp,
  type HistoryEntry,
  type MissionFollowUps,
} from "@/lib/remediation";

type Decision = "REQUEST_CLOSURE" | "CLOSE" | "RETURN";
const decisions: Record<Decision, { title: string; button: string; done: string }> = {
  REQUEST_CLOSURE: { title: "Demander la clôture du suivi", button: "Demander la clôture", done: "Clôture demandée au validateur." },
  CLOSE: { title: "Clôturer le suivi", button: "Clôturer", done: "Suivi clôturé." },
  RETURN: { title: "Renvoyer la demande de clôture", button: "Renvoyer", done: "Demande de clôture renvoyée." },
};

export default function MissionRemediation({ mission }: { mission: Mission }) {
  const user = useUser();
  const [data, setData] = useState<MissionFollowUps | null>(null);
  const [people, setPeople] = useState<{ subject: string; name: string }[]>([]);
  const [history, setHistory] = useState<Record<string, HistoryEntry[]>>({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState<FollowUp | null>(null);
  const [decision, setDecision] = useState<{ followUp: FollowUp; action: Decision } | null>(null);
  const [retry, setRetry] = useState(0);
  const url = `/api/remediation/missions/${mission.id}/follow-ups`;

  useEffect(() => {
    const abort = new AbortController();
    fetch(url, { cache: "no-store", signal: abort.signal })
      .then((r) => readJson<MissionFollowUps>(r))
      .then((value) => {
        setData(value);
        setError("");
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    fetch(`/api/missions/${mission.id}/participants`, { cache: "no-store", signal: abort.signal })
      .then((r) => (r.ok ? r.json() : []))
      .then(setPeople)
      .catch(() => {});
    return () => abort.abort();
  }, [url, mission.id, retry]);

  const manager = canManageMission(user, mission);
  const validator =
    isSuperAdmin(user) ||
    (!!user && !!data && user.roles.includes("AUDIT_VALIDATOR") && data.validatorSubject === user.subject);
  const active = data?.state === "ACTIVE";
  const name = (subject: string | null) =>
    !subject
      ? ""
      : subject === user?.subject
        ? "vous"
        : subject === "audit-service"
          ? "la synchronisation"
          : people.find((p) => p.subject === subject)?.name || "un compte hors équipe";

  async function send(path: string, method: "POST" | "PUT", body: object, done: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch("/api/remediation" + path, {
        method,
        headers: { "Content-Type": "application/json", "x-csrf-token": user!.csrf },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(10000),
      });
      if (!response.ok) await readJson(response);
      setData(await readJson<MissionFollowUps>(await fetch(url, { cache: "no-store" })));
      setHistory({});
      setNotice(done);
      return true;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Action impossible.");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function toggleHistory(followUp: FollowUp) {
    if (history[followUp.id]) {
      const next = { ...history };
      delete next[followUp.id];
      setHistory(next);
      return;
    }
    try {
      const list = await readJson<HistoryEntry[]>(
        await fetch(`/api/remediation/follow-ups/${followUp.id}/history`, { cache: "no-store" }),
      );
      setHistory({ ...history, [followUp.id]: list });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Historique indisponible.");
    }
  }

  const closable = (f: FollowUp) =>
    f.status === "ACTION_PLAN" &&
    f.actions.some((a) => a.status === "VERIFIED") &&
    f.actions.every((a) => a.status === "VERIFIED" || a.status === "CANCELLED");
  const overdue = data?.followUps.reduce((n, f) => n + f.actions.filter((a) => a.overdue).length, 0) || 0;

  return (
    <section className="panel mission-program mission-findings" id="mission-actions" tabIndex={-1} aria-label="Actions correctives">
      <div className="section-label">
        <ListChecks size={20} aria-hidden="true" />
        <h2>Actions correctives</h2>
      </div>
      <p>
        Chaque constat validé reçoit un suivi. Le responsable confie des actions aux audités, l’équipe d’audit
        vérifie leurs justificatifs, puis le validateur clôture le suivi.
      </p>
      {error && <ErrorNotice message={error} />}
      {error && (
        <button className="button secondary" disabled={busy} onClick={() => setRetry((v) => v + 1)}>
          Actualiser les suivis
        </button>
      )}
      {notice && <p role="status">{notice}</p>}
      {!data && !error && <p role="status">Chargement des suivis…</p>}
      {data && data.state === "SUSPENDED" && (
        <p className="program-lock">Mission rouverte dans l’audit : les suivis sont suspendus et en lecture seule.</p>
      )}
      {data && overdue > 0 && (
        <p className="notice" role="note">
          {overdue === 1 ? "Une action a dépassé son échéance." : `${overdue} actions ont dépassé leur échéance.`}
        </p>
      )}
      {data && !data.followUps.length && <p className="panel-message">Aucun constat validé à suivre pour cette mission.</p>}
      <ul className="finding-list">
        {data?.followUps.map((f) => {
          const reference = findingReference(f.reference);
          return (
            <li key={f.id}>
              <article className="finding-card" aria-label={`Suivi ${reference} ${f.title}`}>
                <header>
                  <span className="finding-reference">{reference}</span>
                  <h3>{f.title}</h3>
                  <span className={`status finding-severity-${f.severity.toLowerCase()}`}>
                    Gravité {severityLabels[f.severity].toLowerCase()}
                  </span>
                  <span className={`status followup-status-${f.status.toLowerCase()}`}>
                    <span /> {followUpStatusLabels[f.status]}
                  </span>
                </header>
                {f.recommendation && (
                  <p className="preserve">
                    <strong>Recommandation :</strong> {f.recommendation}
                  </p>
                )}
                {f.returnComment && f.status === "ACTION_PLAN" && (
                  <div className="mission-decision corrections">
                    <h4>Demande de clôture renvoyée</h4>
                    <p className="preserve">{f.returnComment}</p>
                  </div>
                )}
                {f.status === "VERIFICATION" && f.closureRequestedBy && (
                  <p className="program-rating">Clôture demandée par {name(f.closureRequestedBy)}.</p>
                )}
                {f.status === "CLOSED" && f.closedAt && (
                  <p className="program-rating">
                    Clôturé par {name(f.closedBy)} le {new Date(f.closedAt).toLocaleDateString("fr-FR")}.
                  </p>
                )}
                {f.actions.length > 0 ? (
                  <div className="table-scroll">
                    <table className="action-table">
                      <caption className="sr-only">Actions du suivi {reference}</caption>
                      <thead>
                        <tr>
                          <th scope="col">Action</th>
                          <th scope="col">Responsable</th>
                          <th scope="col">Échéance</th>
                          <th scope="col">Statut</th>
                          <th scope="col">
                            <span className="sr-only">Fiche</span>
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        {f.actions.map((a) => (
                          <tr key={a.id}>
                            <td className="preserve">
                              <strong>A{a.reference}</strong> {a.description}
                            </td>
                            <td>
                              {a.ownerName || "Compte inconnu"}
                              {a.ownerActive === false && " (désactivé)"}
                            </td>
                            <td>
                              {dueLabel(a.dueDate)}
                              {a.overdue && <span className="status action-overdue">En retard</span>}
                            </td>
                            <td>
                              <span className={`status action-status-${a.status.toLowerCase()}`}>
                                <span /> {actionStatusLabels[a.status]}
                              </span>
                            </td>
                            <td>
                              <Link href={"/actions/" + a.id} aria-label={`Ouvrir l’action A${a.reference} du suivi ${reference}`}>
                                Ouvrir
                              </Link>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  f.status === "TO_PLAN" && <p className="panel-message">Aucune action planifiée.</p>
                )}
                <div className="row-buttons">
                  {active && manager && (f.status === "TO_PLAN" || f.status === "ACTION_PLAN") && f.actions.length < 20 && (
                    <button
                      className="button primary compact"
                      disabled={busy}
                      onClick={() => {
                        setNotice("");
                        setCreating(f);
                      }}
                      aria-label={`Ajouter une action au suivi ${reference}`}
                    >
                      Ajouter une action
                    </button>
                  )}
                  {active && manager && f.status === "ACTION_PLAN" && (
                    <button
                      className="button secondary compact"
                      disabled={busy || !closable(f)}
                      title={closable(f) ? undefined : "Toutes les actions doivent être vérifiées ou annulées"}
                      onClick={() => setDecision({ followUp: f, action: "REQUEST_CLOSURE" })}
                      aria-label={`Demander la clôture du suivi ${reference}`}
                    >
                      Demander la clôture
                    </button>
                  )}
                  {active && validator && f.status === "VERIFICATION" &&
                    (f.closureRequestedBy === user?.subject ? (
                      <p className="program-rating">Vous avez demandé cette clôture : une autre personne doit décider.</p>
                    ) : (
                      (["CLOSE", "RETURN"] as const).map((action) => (
                        <button
                          key={action}
                          className={`button ${action === "CLOSE" ? "primary" : "secondary"} compact`}
                          disabled={busy}
                          onClick={() => setDecision({ followUp: f, action })}
                          aria-label={`${decisions[action].button} le suivi ${reference}`}
                        >
                          {decisions[action].button}
                        </button>
                      ))
                    ))}
                  <button
                    className="button secondary compact"
                    onClick={() => toggleHistory(f)}
                    aria-expanded={!!history[f.id]}
                    aria-label={`Historique du suivi ${reference}`}
                  >
                    Historique
                  </button>
                </div>
                {history[f.id] && (
                  <ol className="finding-history" aria-label={`Historique du suivi ${reference}`}>
                    {history[f.id].map((h) => (
                      <li key={h.id}>
                        <strong>{remediationHistoryLabels[h.action] || h.action}</strong> par {name(h.actorSubject)} le{" "}
                        {new Date(h.occurredAt).toLocaleString("fr-FR")}
                        {h.override && " (dérogation du superadministrateur)"}
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
      {creating && (
        <ActionEditor
          busy={busy}
          error={error}
          onSubmit={async ({ description, ownerSubject, dueDate }) => {
            if (await send(`/follow-ups/${creating.id}/actions`, "POST", { description, ownerSubject, dueDate }, "Action créée et notifiée à son responsable."))
              setCreating(null);
          }}
          onClose={() => setCreating(null)}
        />
      )}
      {decision && (
        <CommentDialog
          title={`${decisions[decision.action].title} ${findingReference(decision.followUp.reference)}`}
          subtitle={decision.followUp.title}
          button={decisions[decision.action].button}
          required={decision.action === "RETURN"}
          busy={busy}
          error={error}
          onSubmit={async (comment) => {
            const ok = await send(
              `/follow-ups/${decision.followUp.id}/transition`,
              "PUT",
              { version: decision.followUp.version, action: decision.action, comment: comment || null },
              decisions[decision.action].done,
            );
            if (ok) setDecision(null);
          }}
          onClose={() => setDecision(null)}
        />
      )}
    </section>
  );
}
