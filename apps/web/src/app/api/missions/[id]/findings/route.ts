import { findings } from "@/lib/findings-api";
export async function GET(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  return findings(request, (await context.params).id);
}

export const POST = GET;
