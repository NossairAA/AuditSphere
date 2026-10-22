import { evidence } from "@/lib/evidence-api";
export async function GET(
  request: Request,
  context: {
    params: Promise<{ id: string; controlId: string; evidenceId: string }>;
  },
) {
  const { id, controlId, evidenceId } = await context.params;
  return evidence(request, id, controlId, evidenceId);
}
