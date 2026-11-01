"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, ListChecks } from "lucide-react";
import { ErrorNotice } from "@/components/workspace";
import {
  actionReference,
  actionStatusLabels,
  dueLabel,
  readJson,
  type ActionStatus,
  type MyAction,
} from "@/lib/remediation";

export default function Page() {
  const [actions, setActions] = useState<MyAction[] | null>(null);
  const [status, setStatus] = useState("");
  const [overdue, setOverdue] = useState(false);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    const query = new URLSearchParams();
    if (status) query.set("status", status);
    if (overdue) query.set("overdue", "true");
    setActions(null);
    fetch("/api/remediation/me/actions?" + query, { cache: "no-store", signal: abort.signal })
      .then((r) => readJson<MyAction[]>(r))
      .then((list) => {
        setActions(list);
        setError("");
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => abort.abort();
  }, [status, overdue, retry]);
  const late = actions?.filter((a) => a.overdue).length || 0;
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">ACTIONS CORRECTIVES</span>
          <h1>Mes actions</h1>
          <p>Les actions qui vous sont confiées pour corriger les constats d’audit.</p>
        </div>
      </div>
      <section className="panel mission-program" aria-label="Mes actions">
        <div className="directory-filters">
          <label>
            Statut
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">Tous</option>
              {Object.entries(actionStatusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="checkbox-filter">
            <input type="checkbox" checked={overdue} onChange={(e) => setOverdue(e.target.checked)} />
            En retard uniquement
          </label>
        </div>
        {error && <ErrorNotice message={error} />}
        {error && (
          <button className="button secondary" onClick={() => setRetry((v) => v + 1)}>
            Réessayer
          </button>
        )}
        {!actions && !error && <p role="status">Chargement de vos actions…</p>}
        {actions && late > 0 && !overdue && (
          <p className="notice" role="note">
            {late === 1 ? "Une action a dépassé son échéance." : `${late} actions ont dépassé leur échéance.`}
          </p>
        )}
        {actions && !actions.length && (
          <div className="empty">
            <ListChecks size={32} aria-hidden="true" />
            <p>{status || overdue ? "Aucune action ne correspond aux filtres." : "Aucune action ne vous est confiée pour le moment."}</p>
          </div>
        )}
        {actions && actions.length > 0 && (
          <ul className="finding-list">
            {actions.map((a) => {
              const reference = actionReference(a.findingReference, a.reference);
              return (
                <li key={a.id}>
                  <article className="finding-card" aria-label={`${reference} ${a.description}`}>
                    <header>
                      <span className="finding-reference">{reference}</span>
                      <h3 className="action-title">{a.description}</h3>
                      <span className={`status action-status-${a.status.toLowerCase()}`}>
                        <span /> {actionStatusLabels[a.status as ActionStatus]}
                      </span>
                      {a.overdue && <span className="status action-overdue">En retard</span>}
                    </header>
                    <p className="program-rating">Échéance le {dueLabel(a.dueDate)}</p>
                    <Link className="button secondary compact" href={"/actions/" + a.id}>
                      Ouvrir la fiche <ArrowRight size={15} aria-hidden="true" />
                    </Link>
                  </article>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </>
  );
}
