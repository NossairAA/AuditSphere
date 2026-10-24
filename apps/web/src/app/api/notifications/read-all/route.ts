import { notificationRequest } from "@/lib/notifications";
export const PUT = (request: Request) =>
  notificationRequest(request, "read-all");
