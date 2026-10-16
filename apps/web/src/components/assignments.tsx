"use client";
import { useEffect, useState } from "react";
import { useUser, ErrorNotice } from "./workspace";
import type { Mission } from "@/lib/types";
type Person = { subject: string; name: string };
export function AuditorPicker({
  selected,
  onChange,
  disabled = false,
}: {
  selected: string[];
  onChange: (ids: string[]) => void;
  disabled?: boolean;
}) {
  const [people, setPeople] = useState<Person[] | null>(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [page, setPage] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setError("");
    fetch("/api/auditors?page=" + page, {
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (r) => {
        if (!r.ok)
          throw new Error(
            "Annuaire indisponible. Réessayez pour choisir les auditeurs.",
          );
        setPeople(await r.json());
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => controller.abort();
  }, [retry, page]);
  return (
    <fieldset className="auditor-picker" disabled={disabled}>
      <legend>Auditeurs affectés</legend>
      <p>
        Facultatif. Les personnes sélectionnées pourront consulter cette
        mission.
      </p>
      {error ? (
        <>
          <ErrorNotice message={error} />
          <button
            type="button"
            className="button secondary"
            onClick={() => setRetry((v) => v + 1)}
          >
            Recharger l’annuaire
          </button>
        </>
      ) : !people ? (
        <p role="status">Chargement des auditeurs…</p>
      ) : people.length === 0 ? (
        <p>Aucun auditeur disponible.</p>
      ) : (
        people.map((person) => (
          <label key={person.subject} className="auditor-option">
            <input
              type="checkbox"
              checked={selected.includes(person.subject)}
              onChange={(e) =>
                onChange(
                  e.target.checked
                    ? [...selected, person.subject]
                    : selected.filter((id) => id !== person.subject),
                )
              }
            />
            <span>{person.name}</span>
          </label>
        ))
      )}
      <div className="form-actions">
        <button
          type="button"
          className="button secondary"
          disabled={page === 0}
          onClick={() => setPage((p) => p - 1)}
        >
          Page précédente
        </button>
        <span>Page {page + 1}</span>
        <button
          type="button"
          className="button secondary"
          onClick={() => setPage((p) => p + 1)}
        >
          Page suivante
        </button>
      </div>
      {selected
        .filter((id) => people && !people.some((p) => p.subject === id))
        .map((id) => (
          <label key={id} className="auditor-option">
            <input
              type="checkbox"
              checked
              onChange={() => onChange(selected.filter((s) => s !== id))}
            />
            <span>Sélection hors de cette page ({id})</span>
          </label>
        ))}
    </fieldset>
  );
}
export function Participants({
  mission,
  onUpdated,
}: {
  mission: Mission;
  onUpdated: (mission: Mission) => void;
}) {
  const user = useUser();
  const [people, setPeople] = useState<Person[]>([]),
    [selected, setSelected] = useState(mission.assigneeSubjects);
  const [editing, setEditing] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    setSelected(mission.assigneeSubjects);
    const c = new AbortController();
    fetch(`/api/missions/${mission.id}/participants`, {
      cache: "no-store",
      signal: c.signal,
    })
      .then(async (r) => {
        if (!r.ok)
          throw new Error(
            "Les noms des participants sont temporairement indisponibles.",
          );
        setPeople(await r.json());
        setError("");
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => c.abort();
  }, [mission, retry]);
  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const r = await fetch(`/api/missions/${mission.id}/assignments`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          "x-csrf-token": user!.csrf,
        },
        body: JSON.stringify({
          assigneeSubjects: selected,
          version: mission.version,
        }),
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data.error);
      onUpdated(data);
      setEditing(false);
      setNotice("Affectations enregistrées.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Enregistrement impossible");
    } finally {
      setBusy(false);
    }
  }
  const name = (id: string) => people.find((p) => p.subject === id)?.name || id;
  return (
    <section className="panel participants">
      <h2>Participants</h2>
      {error && (
        <>
          <ErrorNotice message={error} />
          <button
            type="button"
            className="button secondary"
            onClick={async () => {
              const r = await fetch(`/api/missions/${mission.id}`, {
                cache: "no-store",
              });
              if (r.ok) {
                onUpdated(await r.json());
                setEditing(false);
                setError("");
              } else setError("Mission inaccessible ou service indisponible.");
            }}
          >
            Recharger la fiche
          </button>
        </>
      )}
      {notice && <p role="status">{notice}</p>}
      <p>
        <strong>Responsable :</strong> {name(mission.ownerSubject)}
      </p>
      {editing ? (
        <form onSubmit={save}>
          <AuditorPicker
            selected={selected}
            onChange={setSelected}
            disabled={busy}
          />
          <div className="form-actions">
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={() => {
                setSelected(mission.assigneeSubjects);
                setEditing(false);
              }}
            >
              Annuler
            </button>
            <button className="button primary" disabled={busy}>
              {busy ? "Enregistrement…" : "Enregistrer les affectations"}
            </button>
          </div>
        </form>
      ) : (
        <>
          <h3>Auditeurs</h3>
          {mission.assigneeSubjects.length ? (
            <ul>
              {mission.assigneeSubjects.map((id) => (
                <li key={id}>{name(id)}</li>
              ))}
            </ul>
          ) : (
            <p>Aucun auditeur affecté.</p>
          )}
          {user?.subject === mission.ownerSubject &&
            user.roles.includes("AUDIT_MANAGER") && (
              <button
                className="button secondary"
                onClick={() => {
                  setEditing(true);
                  setNotice("");
                }}
              >
                Modifier les affectations
              </button>
            )}
        </>
      )}
    </section>
  );
}
