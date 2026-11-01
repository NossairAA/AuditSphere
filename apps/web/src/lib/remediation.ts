// Types et libellés des actions correctives partagés par les écrans (F-006).
export type ActionStatus = "TODO" | "IN_PROGRESS" | "DONE" | "VERIFIED" | "CANCELLED";
export type FollowUpStatus = "TO_PLAN" | "ACTION_PLAN" | "VERIFICATION" | "CLOSED" | "WITHDRAWN";
export type Action = {
  id: string;
  followUpId: string;
  missionId: string;
  findingReference: number;
  reference: number;
  description: string;
  ownerSubject: string;
  ownerName: string | null;
  ownerActive: boolean | null;
  dueDate: string;
  status: ActionStatus;
  overdue: boolean;
  version: number;
  completionNote: string | null;
  completedAt: string | null;
  verifiedBy: string | null;
  verifiedAt: string | null;
  verificationComment: string | null;
  cancelReason: string | null;
  dueDateChanges: number;
  attachmentCount: number;
  createdAt: string;
  updatedAt: string;
};
export type MyAction = Pick<
  Action,
  "id" | "followUpId" | "missionId" | "findingReference" | "reference" | "description" | "status" | "dueDate" | "overdue" | "version"
>;
export type HistoryEntry = {
  id: string;
  actionId: string | null;
  actorSubject: string;
  action: string;
  entityVersion: number;
  reason: string | null;
  override: boolean;
  occurredAt: string;
};
export type FollowUp = {
  id: string;
  reference: number;
  findingVersion: number;
  title: string;
  severity: "LOW" | "MODERATE" | "HIGH" | "CRITICAL";
  conditionText: string;
  cause: string | null;
  recommendation: string | null;
  status: FollowUpStatus;
  version: number;
  closureRequestedBy: string | null;
  closureRequestedAt: string | null;
  closedBy: string | null;
  closedAt: string | null;
  returnComment: string | null;
  actions: Action[];
};
export type MissionFollowUps = {
  missionId: string;
  title: string;
  issueCycle: number;
  state: "ACTIVE" | "SUSPENDED" | "WITHDRAWN";
  syncedAt: string;
  validatorSubject: string | null;
  followUps: FollowUp[];
};
export type Attachment = {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  uploadedBy: string;
  uploadedAt: string;
};
export type ActionDetail = {
  action: Action;
  finding: {
    followUpId: string;
    reference: number;
    title: string;
    severity: FollowUp["severity"];
    conditionText: string;
    cause: string | null;
    recommendation: string | null;
    status: FollowUpStatus;
  };
  progress: { id: string; authorSubject: string; note: string; createdAt: string }[];
  attachments: Attachment[];
  history: HistoryEntry[];
};

export const actionStatusLabels: Record<ActionStatus, string> = {
  TODO: "À faire",
  IN_PROGRESS: "En cours",
  DONE: "Réalisée",
  VERIFIED: "Vérifiée",
  CANCELLED: "Annulée",
};
export const followUpStatusLabels: Record<FollowUpStatus, string> = {
  TO_PLAN: "À planifier",
  ACTION_PLAN: "Plan d’action",
  VERIFICATION: "Clôture demandée",
  CLOSED: "Clôturé",
  WITHDRAWN: "Retiré",
};
export const remediationHistoryLabels: Record<string, string> = {
  CREATED: "Création de l’action",
  UPDATED: "Description modifiée",
  REASSIGNED: "Réaffectation",
  DUE_DATE_CHANGED: "Échéance reportée",
  PROGRESS: "Avancement",
  ATTACHMENT_ADDED: "Justificatif déposé",
  ATTACHMENT_REMOVED: "Justificatif retiré",
  COMPLETED: "Réalisation déclarée",
  VERIFIED: "Vérification",
  RETURNED: "Renvoi au responsable de l’action",
  CANCELLED: "Annulation",
  REOPENED: "Réouverture",
  CLOSURE_REQUESTED: "Clôture demandée",
  CLOSED: "Suivi clôturé",
  CLOSURE_RETURNED: "Demande de clôture renvoyée",
  ISSUED: "Constat émis",
  WITHDRAWN: "Constat retiré",
};
export const actionReference = (finding: number, action: number) =>
  "C-" + String(finding).padStart(2, "0") + " · A" + action;
export const dueLabel = (date: string) => date.split("-").reverse().join("/");
export const sizeLabel = (bytes: number) =>
  bytes < 1024 * 1024 ? Math.max(1, Math.round(bytes / 1024)) + " Kio" : (bytes / 1024 / 1024).toFixed(1) + " Mio";

export async function readJson<T>(response: Response): Promise<T> {
  const data = await response.json().catch(() => ({ error: "Réponse illisible du service" }));
  if (!response.ok) throw new Error(data.error || "Impossible de traiter cette demande");
  return data as T;
}
