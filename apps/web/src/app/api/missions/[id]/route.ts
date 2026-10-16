import { missions } from "@/lib/api";
export async function GET(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  return missions(request, (await context.params).id);
}
