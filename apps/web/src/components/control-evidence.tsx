"use client";
import { useEffect, useState, type FormEvent } from "react";
import { isSuperAdmin, type Mission } from "@/lib/types";
import { useUser, ErrorNotice } from "./workspace";
type Evidence = {
  id: string;
  originalName: string;
  mediaType: string;
  byteSize: number;
  sha256: string;
  authorName: string;
  createdAt: string;
};
export default function ControlEvidence({
  mission,
  controlId,
  onSaved,
}: {
  mission: Mission;
  controlId: string;
  onSaved: () => void;
}) {
  const user = useUser(),
    [open, setOpen] = useState(false),
    [items, setItems] = useState<Evidence[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [revision, setRevision] = useState(0);
  const url = `/api/missions/${mission.id}/controls/${controlId}/evidence`;
  const allowed =
    mission.status === "IN_PROGRESS" &&
    (isSuperAdmin(user) ||
      (!!user &&
        user.roles.includes("AUDITOR") &&
        mission.assigneeSubjects.includes(user.subject)));
  useEffect(() => {
    if (!open) return;
    const abort = new AbortController();
    setBusy(true);
    fetch(url, { signal: abort.signal, cache: "no-store" })
      .then(async (r) => {
        const data = await r.json();
        if (!r.ok) throw new Error(data.error);
        setItems(data);
        setError("");
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      })
      .finally(() => {
        if (!abort.signal.aborted) setBusy(false);
      });
    return () => abort.abort();
  }, [open, url, revision, mission.version]);
  async function upload(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const body = new FormData(form);
    const file = body.get("file");
    if (
      !(file instanceof File) ||
      file.size === 0 ||
      file.size > 10 * 1024 * 1024
    ) {
      setError("Choisissez un fichier de 10 Mio maximum.");
      return;
    }
    body.set("version", String(mission.version));
    setBusy(true);
    setError("");
    try {
      const r = await fetch(url, {
        method: "POST",
        headers: { "x-csrf-token": user!.csrf },
        body,
        signal: AbortSignal.timeout(30000),
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data.error);
      form.reset();
      setRevision((v) => v + 1);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Ajout impossible.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <details
      className="control-result"
      onToggle={(e) => setOpen(e.currentTarget.open)}
    >
      <summary>Preuves du contrôle</summary>
      {open && (
        <>
          <p className="program-rating">
            PDF, JPEG ou PNG, 10 Mio maximum. Les fichiers sont privés et
            téléchargés sur demande.
          </p>
          {error && (
            <>
              <ErrorNotice message={error} />
              <button
                className="button secondary"
                disabled={busy}
                onClick={() => {
                  setRevision((v) => v + 1);
                  onSaved();
                }}
              >
                Actualiser les preuves
              </button>
            </>
          )}
          {busy && <p role="status">Chargement…</p>}
          {!busy && !error && items.length === 0 && (
            <p>Aucune preuve jointe.</p>
          )}
          <ul>
            {items.map((item) => (
              <li key={item.id} className="evidence-item">
                <a href={`${url}/${item.id}`} className="back-link" download>
                  {item.originalName}
                </a>
                <p className="program-rating">
                  {(item.byteSize / 1024).toFixed(1)} Kio · {item.authorName} ·{" "}
                  {new Date(item.createdAt).toLocaleString("fr-FR")}
                </p>
              </li>
            ))}
          </ul>
          {allowed && (
            <form className="program-editor" onSubmit={upload}>
              <fieldset disabled={busy || items.length >= 100}>
                <label htmlFor={`evidence-${controlId}`}>
                  Ajouter une preuve
                </label>
                <input
                  id={`evidence-${controlId}`}
                  type="file"
                  name="file"
                  accept="application/pdf,image/png,image/jpeg"
                  required
                />
                <button className="button secondary" type="submit">
                  Joindre le fichier
                </button>
              </fieldset>
            </form>
          )}
          {!allowed && (
            <p className="program-rating">
              Ajout réservé aux auditeurs affectés pendant une mission en cours.
            </p>
          )}
        </>
      )}
    </details>
  );
}
