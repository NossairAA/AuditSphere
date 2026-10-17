import { notificationRequest } from "@/lib/notifications";
export const dynamic = "force-dynamic";
export const GET = (request: Request) =>
  notificationRequest(request, "unread-count");
