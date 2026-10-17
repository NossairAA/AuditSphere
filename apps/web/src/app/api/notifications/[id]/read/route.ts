import { notificationRequest } from "@/lib/notifications";
export async function PUT(
  request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  return notificationRequest(request, "read", (await params).id);
}
