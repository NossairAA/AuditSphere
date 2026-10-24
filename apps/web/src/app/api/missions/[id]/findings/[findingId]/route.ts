import { findings } from "@/lib/findings-api";
export async function PUT(
  request: Request,
  context: { params: Promise<{ id: string; findingId: string }> },
) {
  const { id, findingId } = await context.params;
  return findings(request, id, findingId);
}

export const DELETE = PUT;
