"use client";
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { ErrorNotice } from "./workspace";
import { readJson, type Action } from "@/lib/remediation";

type Person = { subject: string; name: string; active: boolean };

// Dialogue modal commun, fermé par Échap comme les éditeurs du programme.
function Drawer({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialog.current?.showModal();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="editor-drawer"
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      {children}
    </dialog>
  );
}

export function CommentDialog({
  title,
  subtitle,
  button,
  required,
  busy,
  error,
  onSubmit,
  onClose,
}: {
  title: string;
  subtitle?: string;
  button: string;
  required: boolean;
  busy: boolean;
  error: string;
  onSubmit: (comment: string) => void;
  onClose: () => void;
}) {
  return (
    <Drawer title={title} onClose={onClose}>
      <form
        className="program-editor"
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit(String(new FormData(event.currentTarget).get("comment") || "").trim());
        }}
      >
        <h3>{title}</h3>
        {subtitle && <p>{subtitle}</p>}
        {error && <ErrorNotice message={error} />}
        <fieldset disabled={busy}>
          <label htmlFor="remediation-comment">{required ? "Commentaire obligatoire" : "Commentaire (facultatif)"}</label>
          <textarea autoFocus id="remediation-comment" name="comment" maxLength={2000} rows={4} required={required} />
          <div className="program-editor-actions">
            <button className="button primary" type="submit">
              {busy ? "Enregistrement…" : button}
            </button>
            <button className="button secondary" type="button" onClick={onClose}>
              Annuler
            </button>
          </div>
        </fieldset>
      </form>
    </Drawer>
  );
}

// Création d'une action, ou modification de sa description, de son responsable et de son échéance.
export function ActionEditor({
  action,
  busy,
  error,
  onSubmit,
  onClose,
}: {
  action?: Action;
  busy: boolean;
  error: string;
  onSubmit: (values: { description: string; ownerSubject: string; dueDate: string; reason: string | null }) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState("");
  const [people, setPeople] = useState<Person[]>([]);
  const [owner, setOwner] = useState(action ? action.ownerSubject : "");
  const [searchError, setSearchError] = useState("");
  const [dueDate, setDueDate] = useState(action?.dueDate || "");
  const rescheduled = !!action && !!dueDate && dueDate !== action.dueDate;
  useEffect(() => {
    const abort = new AbortController();
    const timer = setTimeout(() => {
      fetch("/api/remediation/directory/auditees?q=" + encodeURIComponent(query.trim()), {
        cache: "no-store",
        signal: abort.signal,
      })
        .then((r) => readJson<Person[]>(r))
        .then((list) => {
          setPeople(list.filter((p) => p.active));
          setSearchError("");
        })
        .catch((e) => {
          if (e.name !== "AbortError") setSearchError(e.message);
        });
    }, 250);
    return () => {
      clearTimeout(timer);
      abort.abort();
    };
  }, [query]);
  const choices =
    action && !people.some((p) => p.subject === action.ownerSubject)
      ? [{ subject: action.ownerSubject, name: action.ownerName || "Responsable actuel", active: true }, ...people]
      : people;
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    onSubmit({
      description: String(values.get("description") || "").trim(),
      ownerSubject: owner,
      dueDate,
      reason: String(values.get("reason") || "").trim() || null,
    });
  }
  const title = action ? "Modifier l’action" : "Nouvelle action corrective";
  return (
    <Drawer title={title} onClose={onClose}>
      <form className="program-editor" onSubmit={submit}>
        <h3>{title}</h3>
        {error && <ErrorNotice message={error} />}
        <fieldset disabled={busy}>
          <label htmlFor="action-description">Description</label>
          <textarea
            id="action-description"
            name="description"
            maxLength={2000}
            rows={4}
            required
            defaultValue={action?.description || ""}
          />
          <label htmlFor="action-owner-search">Rechercher un audité</label>
          <input
            id="action-owner-search"
            type="search"
            maxLength={100}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Nom ou identifiant"
          />
          {searchError && <ErrorNotice message={searchError} />}
          <label htmlFor="action-owner">Responsable de l’action</label>
          <select id="action-owner" required value={owner} onChange={(e) => setOwner(e.target.value)}>
            <option value="">Choisir un audité</option>
            {choices.map((p) => (
              <option key={p.subject} value={p.subject}>
                {p.name}
              </option>
            ))}
          </select>
          <label htmlFor="action-due">Échéance</label>
          <input id="action-due" type="date" required value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
          {action && (
            <>
              <label htmlFor="action-reason">
                {rescheduled ? "Motif du changement d’échéance (obligatoire)" : "Motif (facultatif)"}
              </label>
              <textarea id="action-reason" name="reason" maxLength={2000} rows={2} required={rescheduled} />
            </>
          )}
          <div className="program-editor-actions">
            <button className="button primary" type="submit">
              {busy ? "Enregistrement…" : action ? "Enregistrer" : "Créer l’action"}
            </button>
            <button className="button secondary" type="button" onClick={onClose}>
              Annuler
            </button>
          </div>
        </fieldset>
      </form>
    </Drawer>
  );
}
