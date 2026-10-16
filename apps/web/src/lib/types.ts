export type Mission = {
  id: string;
  version: number;
  title: string;
  description: string;
  perimeter: string;
  status: string;
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
