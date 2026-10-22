"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import {
  canManage,
  statusLabel,
  plannedDateLabel,
  type Mission,
  type MissionPage,
} from "@/lib/types";
import { ErrorNotice, useUser } from "./workspace";
const statuses = [
  "DRAFT",
  "IN_PROGRESS",
  "PENDING_REVIEW",
  "APPROVED",
  "CLOSED",
] as const;
export default function MissionDirectory() {
  const user = useUser(),
    router = useRouter(),
    path = usePathname(),
    params = useSearchParams();
  const query = params.toString();
  const [data, setData] = useState<MissionPage | null>(null),
    [error, setError] = useState("");
  const [owners, setOwners] = useState<{ subject: string; name: string }[]>([]);
  const [retry, setRetry] = useState(0);
  function update(values: Record<string, string>) {
    const next = new URLSearchParams(query);
    next.delete("page");
    for (const [key, value] of Object.entries(values))
      value ? next.set(key, value) : next.delete(key);
    router.push(path + (next.size ? "?" + next : ""), { scroll: false });
  }
  useEffect(() => {
    if (!user) return;
    const abort = new AbortController();
    setError("");
    setData(null);
    fetch("/api/missions?" + query, { signal: abort.signal, cache: "no-store" })
      .then(async (r) => {
        const body = await r.json();
        if (!r.ok) throw Error(body.error);
        setData(body);
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => abort.abort();
  }, [query, retry, user]);
  useEffect(() => {
    if (!user) return;
    const abort = new AbortController();
    fetch("/api/missions/owners", { signal: abort.signal })
      .then(async (r) => {
        if (r.ok) setOwners(await r.json());
      })
      .catch(() => {});
    return () => abort.abort();
  }, [user, retry]);
  const page = data?.page ?? Math.max(0, Number(params.get("page")) || 0);
  const active = ["q", "status", "owner", "overdue"].some((k) => params.get(k));
  const returnTo = path + (query ? "?" + query : "");
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">ESPACE DE TRAVAIL</span>
          <h1>Missions d’audit</h1>
          <p>Recherchez une mission dans tout votre périmètre.</p>
        </div>
        {canManage(user) && (
          <Link className="button primary" href="/missions/new">
            Nouvelle mission
          </Link>
        )}
      </div>
      <section className="panel">
        <div className="panel-title">
          <h2>Répertoire des missions</h2>
          <span className="count-badge">{data?.total ?? "…"} missions</span>
        </div>
        <form
          className="directory-filters"
          key={query}
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            update(Object.fromEntries([...f].map(([k, v]) => [k, String(v)])));
          }}
        >
          <label>
            Rechercher
            <input
              name="q"
              defaultValue={params.get("q") || ""}
              maxLength={200}
              placeholder="Titre ou périmètre"
            />
          </label>
          <label>
            Statut
            <select
              aria-label="Statut"
              name="status"
              defaultValue={params.get("status") || ""}
            >
              <option value="">Tous les statuts</option>
              {statuses.map((s) => (
                <option key={s} value={s}>
                  {statusLabel(s)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Responsable
            <select
              aria-label="Responsable"
              name="owner"
              defaultValue={params.get("owner") || ""}
            >
              <option value="">Tous les responsables</option>
              {owners.map((o) => (
                <option key={o.subject} value={o.subject}>
                  {o.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Échéance
            <select
              aria-label="Échéance"
              name="overdue"
              defaultValue={params.get("overdue") || ""}
            >
              <option value="">Toutes</option>
              <option value="true">En retard</option>
            </select>
          </label>
          <label>
            Trier par
            <select
              aria-label="Trier par"
              name="sort"
              defaultValue={params.get("sort") || "created"}
            >
              <option value="created">Plus récentes</option>
              <option value="title">Titre</option>
              <option value="end">Fin prévue</option>
            </select>
          </label>
          <button className="button primary">Rechercher</button>
          {active && (
            <Link className="button secondary" href={path}>
              Effacer les filtres
            </Link>
          )}
        </form>
        {error ? (
          <div className="panel-message">
            <ErrorNotice message={error} />
            <button
              className="button secondary"
              onClick={() => setRetry((n) => n + 1)}
            >
              Réessayer
            </button>
          </div>
        ) : !data ? (
          <p className="panel-message" role="status">
            Chargement des missions…
          </p>
        ) : !data.items.length ? (
          <div className="empty">
            <h3>
              {active
                ? "Aucune mission ne correspond aux filtres"
                : "Aucune mission à afficher"}
            </h3>
            <p>
              {active
                ? "Modifiez ou effacez vos filtres."
                : "Les missions créées ou affectées apparaîtront ici."}
            </p>
          </div>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Mission</th>
                  <th>Responsable</th>
                  <th>Statut</th>
                  <th>Fin prévue</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((m: Mission) => (
                  <tr key={m.id}>
                    <td>
                      <Link
                        className="mission-link"
                        href={`/missions/${m.id}?returnTo=${encodeURIComponent(returnTo)}`}
                      >
                        <span>
                          <strong>{m.title}</strong>
                          <small>{m.perimeter}</small>
                        </span>
                      </Link>
                    </td>
                    <td>
                      {owners.find((o) => o.subject === m.ownerSubject)?.name ||
                        (m.ownerSubject === user?.subject
                          ? "Vous"
                          : "Responsable indisponible")}
                    </td>
                    <td>
                      <span
                        className={`status mission-status-${m.status.toLowerCase()}`}
                      >
                        {statusLabel(m.status)}
                      </span>
                    </td>
                    <td>{plannedDateLabel(m.plannedEndDate)}</td>
                    <td>
                      <Link
                        aria-label={"Consulter " + m.title}
                        className="button secondary compact"
                        href={`/missions/${m.id}?returnTo=${encodeURIComponent(returnTo)}`}
                      >
                        Ouvrir
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="table-footer">
          <span>
            {data
              ? `${data.total} résultat${data.total === 1 ? "" : "s"}`
              : "Chargement…"}
          </span>
          <div>
            <button
              className="button secondary compact"
              disabled={!data || page === 0}
              onClick={() => update({ page: String(page - 1) })}
            >
              Précédente
            </button>
            <span>Page {page + 1}</span>
            <button
              className="button secondary compact"
              disabled={!data || (page + 1) * data.size >= data.total}
              onClick={() => update({ page: String(page + 1) })}
            >
              Suivante
            </button>
          </div>
        </div>
      </section>
    </>
  );
}
