import { evidence } from "@/lib/evidence-api";
type Context = { params: Promise<{ id: string; controlId: string }> };
export async function GET(request: Request, context: Context) {
  const { id, controlId } = await context.params;
  return evidence(request, id, controlId);
}
export async function POST(request: Request, context: Context) {
  const { id, controlId } = await context.params;
  return evidence(request, id, controlId);
}
