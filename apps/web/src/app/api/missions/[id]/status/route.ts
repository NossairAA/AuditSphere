import { missions } from "@/lib/api";
export async function PUT(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  return missions(request, (await context.params).id, "status");
}
