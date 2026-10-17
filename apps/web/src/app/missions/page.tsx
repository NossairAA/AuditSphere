"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import {
  ArrowUpRight,
  Plus,
  Search,
  FolderOpen,
  ListFilter,
  ChevronLeft,
  ChevronRight,
  ClipboardList,
  ShieldCheck,
} from "lucide-react";
import { useUser, ErrorNotice } from "@/components/workspace";
import { type MissionPage, dateLabel, statusLabel } from "@/lib/types";
export default function Missions() {
  const user = useUser(),
    [data, setData] = useState<MissionPage | null>(null),
    [error, setError] = useState(""),
    [page, setPage] = useState(0),
    [search, setSearch] = useState(""),
    [retry, setRetry] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    setError("");
    setData(null);
    fetch("/api/missions?page=" + page, {
      cache: "no-store",
      signal: abort.signal,
    })
      .then(async (r) => {
        const body = await r.json();
        if (!r.ok) throw new Error(body.error);
        setData(body);
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => abort.abort();
  }, [page, retry]);
  const items =
    data?.items.filter((m) =>
      (m.title + " " + m.perimeter)
        .toLocaleLowerCase("fr")
        .includes(search.toLocaleLowerCase("fr")),
    ) || [];
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">PILOTAGE DES AUDITS</span>
          <h1>
            Vos missions, en perspective<span className="green">.</span>
          </h1>
          <p>
            Retrouvez vos périmètres d’intervention et préparez vos prochains
            audits.
          </p>
        </div>
        {user?.roles.includes("AUDIT_MANAGER") && (
          <Link className="button primary" href="/missions/new">
            <Plus size={18} /> Nouvelle mission
          </Link>
        )}
      </div>
      <section className="metrics" aria-label="Vue d’ensemble">
        <div className="metric">
          <span>
            Missions accessibles <ClipboardList size={18} />
          </span>
          <strong>{data?.total ?? "…"}</strong>
          <small>Dans votre périmètre</small>
        </div>
        <div className="metric">
          <span>
            Votre rôle <ShieldCheck size={18} />
          </span>
          <strong className="metric-text">
            {user?.roles.includes("AUDIT_MANAGER") ? "Responsable" : "Auditeur"}
          </strong>
          <small>
            {user?.roles.includes("AUDIT_MANAGER")
              ? "Création et suivi des missions"
              : "Consultation des missions affectées"}
          </small>
        </div>
        <div className="metric accent">
          <span>Une mission bien préparée</span>
          <p>
            Un périmètre clair.
            <br />
            Un audit qui avance.
          </p>
          <span className="accent-mark">
            <ArrowUpRight size={27} />
          </span>
        </div>
      </section>
      <section className="panel">
        <div className="panel-title">
          <div>
            <h2>Répertoire des missions</h2>
            <p>Les missions que vous avez créées ou qui vous sont affectées.</p>
          </div>
          <span className="count-badge">
            {data?.total ?? 0} mission{data?.total === 1 ? "" : "s"}
          </span>
        </div>
        <div className="toolbar">
          <label className="search">
            <Search size={18} />
            <input
              aria-label="Rechercher sur cette page"
              placeholder="Rechercher sur cette page…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>
          <span className="toolbar-hint">
            <ListFilter size={15} /> Plus récentes d’abord
          </span>
        </div>
        {error ? (
          <div className="panel-message">
            <ErrorNotice message={error} />
            <button
              className="button secondary"
              onClick={() => setRetry((v) => v + 1)}
            >
              Réessayer
            </button>
          </div>
        ) : !data ? (
          <div className="loading" role="status">
            Chargement des missions…
          </div>
        ) : !items.length ? (
          <div className="empty">
            <FolderOpen size={37} />
            <h3>
              {search
                ? "Aucun résultat sur cette page"
                : "Tout commence par une mission"}
            </h3>
            <p>
              {search
                ? "Essayez un autre titre ou périmètre."
                : "Vos missions apparaîtront ici dès leur création ou leur affectation."}
            </p>
            {user?.roles.includes("AUDIT_MANAGER") && !search && (
              <Link className="button secondary" href="/missions/new">
                Créer votre première mission <Plus size={16} />
              </Link>
            )}
          </div>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>MISSION</th>
                  <th>PÉRIMÈTRE</th>
                  <th>STATUT</th>
                  <th>CRÉATION</th>
                  <th>
                    <span className="sr-only">Consulter</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map((m) => (
                  <tr key={m.id}>
                    <td>
                      <Link href={"/missions/" + m.id} className="mission-link">
                        <span className="mission-icon">
                          <ClipboardList size={19} />
                        </span>
                        <span>
                          <strong>{m.title}</strong>
                          <small>
                            {m.ownerSubject === user?.subject
                              ? "Créée par vous"
                              : "Vous êtes affecté à cette mission"}
                          </small>
                        </span>
                      </Link>
                    </td>
                    <td>{m.perimeter}</td>
                    <td>
                      <span className="status">
                        <span /> {statusLabel(m.status)}
                      </span>
                    </td>
                    <td className="date">{dateLabel(m.createdAt)}</td>
                    <td>
                      <Link
                        className="row-action"
                        aria-label={"Consulter " + m.title}
                        href={"/missions/" + m.id}
                      >
                        <ArrowUpRight size={18} />
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
              ? `${data.total} mission${data.total === 1 ? "" : "s"} accessible${data.total === 1 ? "" : "s"}`
              : "Chargement…"}
          </span>
          <div>
            <button
              className="icon-button"
              aria-label="Page précédente"
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
            >
              <ChevronLeft size={18} />
            </button>
            <span>Page {page + 1}</span>
            <button
              className="icon-button"
              aria-label="Page suivante"
              disabled={!data || (page + 1) * data.size >= data.total}
              onClick={() => setPage((p) => p + 1)}
            >
              <ChevronRight size={18} />
            </button>
          </div>
        </div>
      </section>
    </>
  );
}
