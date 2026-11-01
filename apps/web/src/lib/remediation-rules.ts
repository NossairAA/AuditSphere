// Règles BFF des actions correctives (F-006), sans dépendance serveur pour rester testables en unitaire.
export type RemediationRoute =
  | { kind: "followUps"; path: string }
  | { kind: "followUpHistory"; path: string }
  | { kind: "followUpTransition"; path: string }
  | { kind: "createAction"; path: string }
  | { kind: "action"; path: string }
  | { kind: "updateAction"; path: string }
  | { kind: "actionTransition"; path: string }
  | { kind: "progress"; path: string }
  | { kind: "upload"; path: string }
  | { kind: "download"; path: string }
  | { kind: "removeAttachment"; path: string }
  | { kind: "myActions"; path: string }
  | { kind: "auditees"; path: string };

const id = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
const routes: [string, RegExp, RemediationRoute["kind"]][] = [
  ["GET", new RegExp(`^missions/${id}/follow-ups$`, "i"), "followUps"],
  ["GET", new RegExp(`^follow-ups/${id}/history$`, "i"), "followUpHistory"],
  ["PUT", new RegExp(`^follow-ups/${id}/transition$`, "i"), "followUpTransition"],
  ["POST", new RegExp(`^follow-ups/${id}/actions$`, "i"), "createAction"],
  ["GET", new RegExp(`^actions/${id}$`, "i"), "action"],
  ["PUT", new RegExp(`^actions/${id}$`, "i"), "updateAction"],
  ["PUT", new RegExp(`^actions/${id}/transition$`, "i"), "actionTransition"],
  ["POST", new RegExp(`^actions/${id}/progress$`, "i"), "progress"],
  ["POST", new RegExp(`^actions/${id}/attachments$`, "i"), "upload"],
  ["GET", new RegExp(`^actions/${id}/attachments/${id}$`, "i"), "download"],
  ["DELETE", new RegExp(`^actions/${id}/attachments/${id}$`, "i"), "removeAttachment"],
  ["GET", /^me\/actions$/, "myActions"],
  ["GET", /^directory\/auditees$/, "auditees"],
];

// Seules les routes de remediation-service utilisées par l'interface sont relayées.
export function remediationRoute(method: string, segments: string[]): RemediationRoute | null {
  const resource = segments.join("/");
  const match = routes.find(([m, pattern]) => m === method && pattern.test(resource));
  return match ? ({ kind: match[2], path: "/remediation/" + resource } as RemediationRoute) : null;
}

// Paramètres de recherche admis par route, avec leur forme.
export function remediationQuery(kind: RemediationRoute["kind"], search: URLSearchParams) {
  const query = new URLSearchParams();
  if (kind === "myActions") {
    const status = search.get("status");
    if (status && /^(TODO|IN_PROGRESS|DONE|VERIFIED|CANCELLED)$/.test(status)) query.set("status", status);
    if (search.get("overdue") === "true") query.set("overdue", "true");
  }
  if (kind === "auditees") query.set("q", (search.get("q") || "").slice(0, 100));
  return query;
}

const text = (value: unknown) => (typeof value === "string" ? value : null);

// Liste blanche des champs transmis, le reste du formulaire est ignoré.
export function remediationBody(kind: RemediationRoute["kind"], input: Record<string, unknown>) {
  switch (kind) {
    case "followUpTransition":
    case "actionTransition":
      return { version: input.version, action: input.action, comment: text(input.comment) };
    case "createAction":
      return { description: input.description, ownerSubject: input.ownerSubject, dueDate: input.dueDate };
    case "updateAction":
      return {
        version: input.version,
        ...(input.description !== undefined ? { description: input.description } : {}),
        ...(input.ownerSubject !== undefined ? { ownerSubject: input.ownerSubject } : {}),
        ...(input.dueDate !== undefined ? { dueDate: input.dueDate } : {}),
        reason: text(input.reason),
      };
    case "progress":
      return { version: input.version, note: input.note };
    default:
      return null;
  }
}

export function validVersion(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

export function remediationError(status: number, kind: RemediationRoute["kind"], action?: unknown) {
  if (status === 401) return "Votre session a expiré. Reconnectez-vous.";
  if (status === 404 && kind === "followUps")
    return "Aucun suivi pour cette mission : la synchronisation suit l’approbation de quelques secondes, ou votre accès ne le permet pas.";
  if (status === 404)
    return kind === "download" || kind === "removeAttachment"
      ? "Justificatif introuvable ou accès non autorisé"
      : "Suivi ou action introuvable, ou accès non autorisé";
  if (status === 403) {
    if (action === "CLOSE") return "Clôture refusée : seul le validateur désigné clôture, jamais la personne qui l’a demandée.";
    if (action === "VERIFY" || action === "RETURN")
      return "Décision refusée : votre rôle ne le permet pas ou cette action vous est confiée.";
    return "Votre rôle ne permet pas cette action";
  }
  if (status === 413) return "Fichier trop volumineux, 10 Mio au plus";
  if (status === 400)
    return kind === "upload"
      ? "Seuls les fichiers PDF, JPEG et PNG valides sont acceptés"
      : kind === "updateAction"
        ? "Vérifiez les champs : un changement d’échéance exige un motif, et l’échéance ne peut être passée"
        : kind === "createAction"
        ? "Vérifiez les champs : l’échéance ne peut être passée et le responsable doit être un audité actif"
        : "Vérifiez les champs du formulaire";
  if (status === 409) {
    if (action === "COMPLETE")
      return "Réalisation impossible : déposez au moins un justificatif et rechargez si l’action a changé.";
    if (action === "REQUEST_CLOSURE")
      return "Demande impossible : toutes les actions doivent être vérifiées ou annulées, avec au moins une vérifiée.";
    if (kind === "createAction") return "Création impossible : le suivi n’accepte plus d’action ou en compte déjà 20.";
    if (kind === "upload") return "Dépôt impossible : action réalisée, dix justificatifs déjà déposés ou quota atteint.";
    return "Ce suivi ou cette action a changé, ou son statut ne permet plus cette opération. Rechargez avant de recommencer.";
  }
  return "Impossible de traiter cette demande";
}
