// Règles BFF des constats, sans dépendance serveur pour rester testables en unitaire.
export type FindingResource = "history" | "transition";

const uuid = /^[0-9a-f-]{36}$/i;
const decisions = ["VALIDATE", "RETURN", "REJECT", "REOPEN"];

export function validIds(...ids: (string | undefined)[]) {
  return ids.filter((id) => id !== undefined).every((id) => uuid.test(id!));
}

// Liste blanche des champs transmis, le reste du formulaire est ignoré.
export function findingBody(
  method: string,
  resource: FindingResource | undefined,
  input: Record<string, unknown>,
) {
  if (resource === "transition")
    return {
      version: input.version,
      action: input.action,
      comment: input.comment ?? null,
    };
  if (method === "DELETE") return null;
  return {
    ...(method === "PUT" ? { version: input.version } : {}),
    title: input.title,
    condition: input.condition,
    criterion: input.criterion ?? null,
    cause: input.cause ?? null,
    consequence: input.consequence ?? null,
    recommendation: input.recommendation ?? null,
    severity: input.severity,
    controlIds: input.controlIds,
  };
}

export function requiredRole(resource: FindingResource | undefined, action: unknown) {
  return resource === "transition" && typeof action === "string" && decisions.includes(action)
    ? "AUDIT_MANAGER"
    : "AUDITOR";
}

export function findingPath(
  missionId: string,
  findingId: string | undefined,
  resource: FindingResource | undefined,
  version?: number,
) {
  return (
    `/missions/${missionId}/findings` +
    (findingId ? "/" + findingId : "") +
    (resource ? "/" + resource : "") +
    (version === undefined ? "" : "?version=" + version)
  );
}

export function findingError(
  status: number,
  method: string,
  resource: FindingResource | undefined,
  action: unknown,
) {
  if (status === 409) {
    if (method === "POST")
      return "Création impossible : la mission doit être En cours et compter moins de 100 constats.";
    if (action === "SUBMIT")
      return "Soumission impossible : complétez la cause et la recommandation, liez un contrôle Non conforme et rechargez si le constat a changé.";
    return "Ce constat a été modifié ou son statut ne permet plus cette action. Rechargez la liste avant de recommencer.";
  }
  if (status === 403)
    return resource === "transition" && typeof action === "string" && decisions.includes(action)
      ? "Décision refusée : votre rôle ne le permet pas ou vous avez soumis ce constat."
      : "Votre rôle ne permet pas cette action";
  if (status === 404) return "Constat ou mission introuvable, ou accès non autorisé";
  if (status === 400) return "Vérifiez les champs du formulaire";
  if (status === 401) return "Votre session a expiré. Reconnectez-vous.";
  return "Impossible de traiter cette demande";
}

// Corps 409 émis par audit-service quand des constats attendent une décision.
export function pendingFindingsError(body: unknown) {
  if (
    !body ||
    typeof body !== "object" ||
    (body as { code?: unknown }).code !== "FINDINGS_PENDING"
  )
    return null;
  const pending = (body as { pending?: unknown }).pending;
  if (typeof pending !== "number" || !Number.isInteger(pending) || pending < 1) return null;
  return {
    code: "FINDINGS_PENDING",
    pending,
    error:
      pending === 1
        ? "Un constat attend encore une décision. Validez-le ou rejetez-le avant de continuer."
        : `${pending} constats attendent encore une décision. Validez-les ou rejetez-les avant de continuer.`,
  };
}
