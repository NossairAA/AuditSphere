export type Mission = {
  id: string;
  version: number;
  title: string;
  description: string;
  perimeter: string;
  status: "DRAFT" | "IN_PROGRESS" | "PENDING_REVIEW" | "APPROVED" | "CLOSED";
  plannedStartDate: string | null;
  plannedEndDate: string | null;
  startedAt: string | null;
  closedAt: string | null;
  withdrawnAt: string | null;
  withdrawalReason: string | null;
  submittedAt: string | null;
  reviewedAt: string | null;
  reviewedBy: string | null;
  reviewDecision: "APPROVED" | "REJECTED" | null;
  reviewComment: string | null;
  ownerSubject: string;
  validatorSubject: string | null;
  assigneeSubjects: string[];
  createdAt: string;
};
export type MissionPage = {
  items: Mission[];
  page: number;
  size: number;
  total: number;
};
export const dateLabel = (date: string) =>
  new Intl.DateTimeFormat("fr-FR", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(new Date(date));

export const statusLabel = (status: Mission["status"]) =>
  ({
    DRAFT: "Brouillon",
    IN_PROGRESS: "En cours",
    PENDING_REVIEW: "En attente de validation",
    APPROVED: "Approuvée",
    CLOSED: "Clôturée",
  })[status];
export const plannedDateLabel = (date: string | null) =>
  date ? date.split("-").reverse().join("/") : "Non définie";

export const roleLabels = (roles: string[] = []) =>
  [
    ["AUDIT_MANAGER", "Responsable"],
    ["AUDITOR", "Auditeur"],
    ["AUDIT_VALIDATOR", "Validateur"],
  ]
    .filter(([role]) => roles.includes(role))
    .map(([, label]) => label)
    .join(" · ") || "Utilisateur";
