import { missions } from "@/lib/api";
export async function GET(request: Request) {
  return missions(request, undefined, "validators");
}
