"use client";

import { useEffect, useState } from "react";
import { ErrorNotice } from "./workspace";

type Person = { subject: string; name: string };

export function ValidatorPicker({
  selected,
  onChange,
  disabled = false,
}: {
  selected: string;
  onChange: (subject: string) => void;
  disabled?: boolean;
}) {
  const [people, setPeople] = useState<Person[]>([]);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [selectedName, setSelectedName] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    fetch(`/api/validators?page=${page}`, {
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok)
          throw new Error("Annuaire des validateurs indisponible.");
        return (await response.json()) as Person[];
      })
      .then((items) => {
        setPeople(items);
        const current = items.find((person) => person.subject === selected);
        if (current) setSelectedName(current.name);
      })
      .catch((cause) => {
        if (cause.name !== "AbortError") setError(cause.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [page, retry]);

  return (
    <fieldset className="auditor-picker" disabled={disabled}>
      <legend>Validateur de la mission</legend>
      <p>
        Facultatif en brouillon. Une approbation sera nécessaire avant la
        clôture.
      </p>
      <label htmlFor="validatorSubject">Choisir un validateur</label>
      <select
        id="validatorSubject"
        value={selected}
        onChange={(event) => {
          onChange(event.target.value);
          setSelectedName(
            people.find((person) => person.subject === event.target.value)
              ?.name || "",
          );
        }}
        disabled={disabled || loading || !!error}
      >
        <option value="">Aucun validateur pour le moment</option>
        {selected && !people.some((person) => person.subject === selected) && (
          <option value={selected}>
            {selectedName || "Validateur sélectionné"}
          </option>
        )}
        {people.map((person) => (
          <option key={person.subject} value={person.subject}>
            {person.name}
          </option>
        ))}
      </select>
      {loading && <p role="status">Chargement des validateurs…</p>}
      {error && (
        <>
          <ErrorNotice message={error} />
          <button
            type="button"
            className="button secondary"
            onClick={() => setRetry((value) => value + 1)}
          >
            Recharger les validateurs
          </button>
        </>
      )}
      <div className="form-actions">
        <button
          type="button"
          className="button secondary"
          disabled={page === 0 || loading}
          onClick={() => setPage((value) => value - 1)}
        >
          Page précédente des validateurs
        </button>
        <span>Page {page + 1}</span>
        <button
          type="button"
          className="button secondary"
          disabled={loading || page === 10000}
          onClick={() => setPage((value) => value + 1)}
        >
          Page suivante des validateurs
        </button>
      </div>
    </fieldset>
  );
}
