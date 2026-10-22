import { missions } from "@/lib/api";
export const GET = (request: Request) => missions(request, undefined, "owners");
