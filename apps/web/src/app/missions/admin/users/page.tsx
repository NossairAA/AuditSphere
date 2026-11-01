"use client";
import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ErrorNotice, useUser } from "@/components/workspace";
import { isSuperAdmin } from "@/lib/types";
import { useUnsavedGuard } from "@/components/unsaved-guard";
import { Pagination } from "@/components/pagination";
// Page size of the admin user listing, set by the admin API.
const PAGE_SIZE = 20;
type Person = {
  id: string;
  username: string;
  firstName: string;
  lastName: string;
  email: string;
  enabled: boolean;
  roles: string[];
};
const roles: Record<string, string> = {
  AUDIT_MANAGER: "Responsable",
  AUDITOR: "Auditeur",
  AUDIT_VALIDATOR: "Validateur",
  AUDITEE: "Audité",
  SUPER_ADMIN: "Superadmin",
};
type History = {
  activeMissions: number;
  events: {
    id: string;
    actor_subject: string;
    action: string;
    reason: string;
    outcome: string;
    created_at: string;
    requested_changes?: string;
  }[];
};
function Users() {
  const user = useUser(),
    params = useSearchParams(),
    router = useRouter();
  const query = params.toString();
  const [data, setData] = useState<{
      items: Person[];
      total: number;
      page: number;
    } | null>(null),
    [error, setError] = useState(""),
    [retry, setRetry] = useState(0);
  const [editor, setEditor] = useState<Person | "new" | null>(null),
    [history, setHistory] = useState<History | null>(null),
    [busy, setBusy] = useState(false),
    [dirty, setDirty] = useState(false),
    [notice, setNotice] = useState("");
  const [formError, setFormError] = useState("");
  const dialog = useRef<HTMLDialogElement>(null);
  useUnsavedGuard(dirty);
  function update(values: Record<string, string>) {
    const next = new URLSearchParams(query);
    next.delete("page");
    for (const [k, v] of Object.entries(values))
      v ? next.set(k, v) : next.delete(k);
    router.push("/missions/admin/users?" + next, { scroll: false });
  }
  useEffect(() => {
    if (!isSuperAdmin(user)) return;
    const abort = new AbortController();
    setError("");
    setData(null);
    fetch("/api/admin/users?" + query, { signal: abort.signal })
      .then(async (r) => {
        const body = await r.json();
        if (!r.ok) throw Error(body.error);
        setData(body);
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => abort.abort();
  }, [user, query, retry]);
  useEffect(() => {
    if (editor) dialog.current?.showModal();
    else dialog.current?.close();
    setHistory(null);
    setFormError("");
    if (editor) setNotice("");
    if (editor && editor !== "new") {
      const abort = new AbortController();
      fetch(`/api/admin/users/${editor.id}/history`, { signal: abort.signal })
        .then(async (r) => {
          const body = await r.json();
          if (!r.ok) throw Error(body.error);
          setHistory(body);
        })
        .catch((e) => {
          if (e.name !== "AbortError") setFormError(e.message);
        });
      return () => abort.abort();
    }
  }, [editor]);
  function close() {
    if (busy) return;
    if (!dirty || confirm("Quitter sans enregistrer ?")) {
      setDirty(false);
      setNotice("");
      setEditor(null);
    }
  }
  async function submit(form: HTMLFormElement, reset = false) {
    if (!editor || !user) return;
    const f = new FormData(form),
      reason = String(f.get("reason") || "");
    if (!reason.trim()) {
      setFormError("Indiquez le motif de cette opération.");
      return;
    }
    const selected = f.getAll("roles").map(String);
    if (
      !confirm(
        reset
          ? "Réinitialiser l’accès et fermer les sessions de cet utilisateur ?"
          : selected.includes("SUPER_ADMIN")
            ? "Confirmer ces droits, dont l’accès superadmin, et enregistrer cette opération ?"
            : "Confirmer les modifications de ce compte et fermer ses sessions ?",
      )
    )
      return;
    setBusy(true);
    setFormError("");
    setNotice("");
    try {
      const path =
        editor === "new"
          ? "/api/admin/users"
          : `/api/admin/users/${editor.id}${reset ? "/reset-password" : ""}`;
      const body = reset
        ? { reason }
        : {
            username: editor === "new" ? f.get("username") : editor.username,
            firstName: f.get("firstName"),
            lastName: f.get("lastName"),
            email: f.get("email"),
            enabled: f.get("enabled") === "on",
            roles: selected,
            reason,
          };
      const r = await fetch(path, {
        method: editor === "new" || reset ? "POST" : "PUT",
        headers: {
          "Content-Type": "application/json",
          "x-csrf-token": user.csrf,
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(35000),
      });
      const result = await r.json();
      if (!r.ok) throw Error(result.error);
      setDirty(false);
      setRetry((n) => n + 1);
      setNotice(result.status === "LOCAL_ACTION_REQUIRED"
        ? "Email indisponible en local. Un opérateur doit fournir l’accès initial dans Keycloak."
        : "Un email de définition du mot de passe a été envoyé à l’utilisateur.");
      setEditor(null);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : "Opération impossible.");
    } finally {
      setBusy(false);
    }
  }
  if (user && !isSuperAdmin(user))
    return <ErrorNotice message="Accès réservé au superadministrateur." />;
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">ADMINISTRATION</span>
          <h1>Utilisateurs</h1>
          <p>Gérez les accès et les rôles de l’application.</p>
        </div>
        <button className="button primary" onClick={() => setEditor("new")}>
          Créer un utilisateur
        </button>
      </div>
      <section className="panel">
        {notice && <p className="notice" role="status">{notice}</p>}
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
              maxLength={200}
              defaultValue={params.get("q") || ""}
              placeholder="Nom, identifiant ou email"
            />
          </label>
          <label>
            Rôle
            <select name="role" defaultValue={params.get("role") || ""}>
              <option value="">Tous les rôles</option>
              {Object.entries(roles).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </label>
          <label>
            État
            <select name="enabled" defaultValue={params.get("enabled") || ""}>
              <option value="">Tous</option>
              <option value="true">Actifs</option>
              <option value="false">Désactivés</option>
            </select>
          </label>
          <button className="button primary">Rechercher</button>
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
            Chargement des utilisateurs…
          </p>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Utilisateur</th>
                  <th>Email</th>
                  <th>Rôles</th>
                  <th>État</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <strong>
                        {p.firstName} {p.lastName}
                      </strong>
                      <p>{p.username}</p>
                    </td>
                    <td>{p.email}</td>
                    <td>
                      {p.roles.map((r) => roles[r]).join(", ") ||
                        "Aucun rôle métier"}
                    </td>
                    <td>{p.enabled ? "Actif" : "Désactivé"}</td>
                    <td>
                      <button
                        className="button secondary compact"
                        onClick={() => setEditor(p)}
                      >
                        Gérer
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!data.items.length && (
              <p className="panel-message">
                Aucun utilisateur ne correspond aux filtres.
              </p>
            )}
          </div>
        )}
        <Pagination
          page={data?.page ?? Math.max(0, Number(params.get("page")) || 0)}
          size={PAGE_SIZE}
          total={data?.total}
          unit={["utilisateur", "utilisateurs"]}
          onPage={(next) => update({ page: String(next) })}
        />
      </section>
      {editor && (
        <dialog
          ref={dialog}
          className="editor-drawer"
          aria-labelledby="user-editor-title"
          onCancel={(e) => {
            e.preventDefault();
            close();
          }}
        >
          <h2 id="user-editor-title">
            {editor === "new"
              ? "Créer un utilisateur"
              : `Gérer ${editor.username}`}
          </h2>
          {formError && <ErrorNotice message={formError} />}
          <form
              className="admin-user-form"
              onChange={() => setDirty(true)}
              onSubmit={(e) => {
                e.preventDefault();
                submit(e.currentTarget);
              }}
            >
              <fieldset disabled={busy}>
                {editor === "new" && (
                  <label>
                    Identifiant
                    <input
                      name="username"
                      pattern="[a-zA-Z0-9._-]{3,80}"
                      minLength={3}
                      maxLength={80}
                      autoFocus
                      required
                      autoComplete="off"
                    />
                  </label>
                )}
                <label>
                  Prénom
                  <input
                    name="firstName"
                    maxLength={100}
                    required
                    defaultValue={editor === "new" ? "" : editor.firstName}
                  />
                </label>
                <label>
                  Nom
                  <input
                    name="lastName"
                    maxLength={100}
                    required
                    defaultValue={editor === "new" ? "" : editor.lastName}
                  />
                </label>
                <label>
                  Email
                  <input
                    name="email"
                    type="email"
                    maxLength={254}
                    required
                    readOnly={editor !== "new"}
                    defaultValue={editor === "new" ? "" : editor.email}
                  />
                </label>
                <fieldset>
                  <legend>Rôles applicatifs</legend>
                  {Object.entries(roles).map(([k, v]) => (
                    <label key={k}>
                      <input
                        type="checkbox"
                        name="roles"
                        value={k}
                        defaultChecked={
                          editor !== "new" && editor.roles.includes(k)
                        }
                      />
                      {v}
                    </label>
                  ))}
                </fieldset>
                <label>
                  <input
                    type="checkbox"
                    name="enabled"
                    defaultChecked={editor === "new" || editor.enabled}
                  />
                  Compte actif
                </label>
                {editor !== "new" && (
                  <p role="note">
                    {history
                      ? `${history.activeMissions} mission(s) active(s) liée(s) à ce compte.`
                      : "Chargement des missions liées…"}{" "}
                    Les affectations et historiques sont conservés. Les
                    modifications fermeront ses sessions.
                  </p>
                )}
                <label>
                  Motif
                  <textarea name="reason" required maxLength={2000} rows={3} />
                </label>
                <div className="row-buttons">
                  <button className="button primary" disabled={busy}>
                    {busy ? "Enregistrement…" : "Enregistrer"}
                  </button>
                  {editor !== "new" && (
                    <button
                      type="button"
                      className="button secondary"
                      onClick={(e) => submit(e.currentTarget.form!, true)}
                    >
                      Réinitialiser l’accès
                    </button>
                  )}
                  <button
                    type="button"
                    className="button secondary"
                    onClick={close}
                  >
                    Annuler
                  </button>
                </div>
              </fieldset>
            </form>
          {history && (
            <details>
              <summary>Historique des opérations</summary>
              {history.events.length ? (
                history.events.map((e) => (
                  <article key={e.id}>
                    <strong>
                      {(
                        {
                          CREATE: "Création",
                          UPDATE: "Modification",
                          RESET_PASSWORD: "Réinitialisation d’accès",
                        } as Record<string, string>
                      )[e.action] || e.action}{" "}
                      ·{" "}
                      {(
                        {
                          PENDING: "En cours",
                          SUCCESS: "Confirmée",
                          UNCERTAIN: "À vérifier",
                        } as Record<string, string>
                      )[e.outcome] || e.outcome}
                    </strong>
                    <p>{new Date(e.created_at).toLocaleString("fr-FR")}</p>
                    <p>{e.reason}</p>
                    {e.requested_changes && e.requested_changes !== "{}" && (
                      <details>
                        <summary>Modifications demandées</summary>
                        <RequestedChanges value={e.requested_changes} />
                      </details>
                    )}
                    <small>Auteur : {e.actor_subject}</small>
                  </article>
                ))
              ) : (
                <p>Aucune opération enregistrée.</p>
              )}
            </details>
          )}
        </dialog>
      )}
    </>
  );
}
function RequestedChanges({ value }: { value: string }) {
  const fields = JSON.parse(value) as {
    firstName?: string;
    lastName?: string;
    email?: string;
    enabled?: boolean;
    roles?: string[];
  };
  return (
    <dl>
      <dt>Nom</dt>
      <dd>
        {fields.firstName} {fields.lastName}
      </dd>
      <dt>Email</dt>
      <dd>{fields.email}</dd>
      <dt>État</dt>
      <dd>{fields.enabled ? "Actif" : "Désactivé"}</dd>
      <dt>Rôles</dt>
      <dd>
        {fields.roles?.map((r) => roles[r] || r).join(", ") ||
          "Aucun rôle métier"}
      </dd>
    </dl>
  );
}
export default function Page() {
  return (
    <Suspense fallback={<p>Chargement…</p>}>
      <Users />
    </Suspense>
  );
}
