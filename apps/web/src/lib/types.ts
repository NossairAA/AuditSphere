export type Mission = {
  id: string;
  version: number;
  title: string;
  description: string;
  perimeter: string;
  status: "DRAFT" | "IN_PROGRESS" | "CLOSED";
  plannedStartDate: string | null;
  plannedEndDate: string | null;
  startedAt: string | null;
  closedAt: string | null;
  ownerSubject: string;
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
  ({ DRAFT: "Brouillon", IN_PROGRESS: "En cours", CLOSED: "Clôturée" })[status];
export const plannedDateLabel = (date: string | null) =>
  date ? date.split("-").reverse().join("/") : "Non définie";
