"use client";
import { canManageMission } from "@/lib/types";
import { useEffect, useState } from "react";
import { type Mission } from "@/lib/types";
import { ErrorNotice, useUser } from "./workspace";
type Person = { subject: string; name: string };
export default function MissionValidator({
  mission,
  onUpdated,
}: {
  mission: Mission;
  onUpdated: (mission: Mission) => void;
}) {
  const user = useUser();
  const canEdit =
    canManageMission(user, mission) &&
    ["DRAFT", "IN_PROGRESS"].includes(mission.status);
  const [editing, setEditing] = useState(!mission.validatorSubject);
  const [people, setPeople] = useState<Person[]>([]),
    [page, setPage] = useState(0),
    [selected, setSelected] = useState(mission.validatorSubject || ""),
    [name, setName] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [loading, setLoading] = useState(false),
    [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/missions/${mission.id}/participants`, {
      signal: controller.signal,
    })
      .then(async (r) => {
        if (!r.ok) throw new Error("Nom du validateur indisponible.");
        return r.json();
      })
      .then((items: Person[]) =>
        setName(
          items.find((p) => p.subject === mission.validatorSubject)?.name ||
            "Compte indisponible",
        ),
      )
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => controller.abort();
  }, [mission.id, mission.validatorSubject, retry]);
  useEffect(() => {
    if (!canEdit || !editing) return;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    setPeople([]);
    fetch(`/api/validators?page=${page}`, { signal: controller.signal })
      .then(async (r) => {
        const data = await r.json();
        if (!r.ok) throw new Error(data.error);
        return data;
      })
      .then(setPeople)
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [canEdit, editing, page, retry]);
  async function save() {
    setBusy(true);
    setError("");
    try {
      const r = await fetch(`/api/missions/${mission.id}/validator`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          "x-csrf-token": user!.csrf,
        },
        body: JSON.stringify({
          version: mission.version,
          validatorSubject: selected || null,
        }),
        signal: AbortSignal.timeout(10000),
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data.error);
      onUpdated(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Désignation impossible.");
    } finally {
      setBusy(false);
    }
  }
  async function reload() {
    setBusy(true);
    setError("");
    try {
      const r = await fetch(`/api/missions/${mission.id}`, {
        cache: "no-store",
        signal: AbortSignal.timeout(10000),
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data.error);
      onUpdated(data);
      setSelected(data.validatorSubject || "");
      setRetry((v) => v + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Rechargement impossible.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section
      className="panel participants"
      aria-label="Validateur de la mission"
    >
      <h2>Validateur de la mission</h2>
      <p>
        Validateur désigné :{" "}
        {mission.validatorSubject ? name || "Chargement…" : "Aucun"}
      </p>
      {error && (
        <>
          <ErrorNotice message={error} />
          <button className="button secondary" disabled={busy} onClick={reload}>
            Recharger le validateur
          </button>
        </>
      )}
      {canEdit && !editing && (
        <button className="button secondary" onClick={() => setEditing(true)}>
          Modifier le validateur
        </button>
      )}
      {canEdit && editing && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          <label>
            Choisir un validateur
            <select
              value={selected}
              onChange={(e) => setSelected(e.target.value)}
              disabled={busy || loading}
            >
              <option value="">Aucun validateur</option>
              {selected && !people.some((p) => p.subject === selected) && (
                <option value={selected}>
                  {selected === mission.validatorSubject
                    ? name || "Validateur actuel"
                    : "Sélection conservée"}
                </option>
              )}
              {people.map((p) => (
                <option key={p.subject} value={p.subject}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <div className="form-actions">
            {(page > 0 || people.length >= 50) && (
              <>
                <button
                  type="button"
                  className="button secondary"
                  disabled={page === 0 || busy || loading}
                  onClick={() => setPage((v) => v - 1)}
                >
                  Page précédente des validateurs
                </button>
                <span>Page {page + 1}</span>
                <button
                  type="button"
                  className="button secondary"
                  disabled={
                    page === 10000 || people.length < 50 || busy || loading
                  }
                  onClick={() => setPage((v) => v + 1)}
                >
                  Page suivante des validateurs
                </button>
              </>
            )}
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={() => {
                setSelected(mission.validatorSubject || "");
                setEditing(false);
              }}
            >
              Annuler la modification du validateur
            </button>
            <button className="button primary" disabled={busy || loading}>
              Enregistrer le validateur
            </button>
          </div>
          {loading && <p role="status">Chargement des validateurs…</p>}
        </form>
      )}
      <p>
        La désignation donne accès à cette mission. Son approbation est
        obligatoire avant la clôture.
      </p>
    </section>
  );
}
