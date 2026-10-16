import { json, session } from "@/lib/auth";
import { rateLimit } from "@/lib/rate";
export const dynamic = "force-dynamic";
export async function GET(request: Request) {
  const rejection = rateLimit(request);
  if (rejection) return rejection;
  try {
    const data = await session();
    if (!data)
      return json({ error: "Votre session a expiré. Reconnectez-vous." }, 401);
    const url = new URL(
      "/notifications",
      process.env.API_ORIGIN || "http://localhost:8080",
    );
    url.searchParams.set(
      "page",
      new URL(request.url).searchParams.get("page") || "0",
    );
    url.searchParams.set("size", "20");
    const response = await fetch(url, {
      headers: { Authorization: "Bearer " + data.accessToken },
      cache: "no-store",
      signal: AbortSignal.timeout(10000),
    });
    if (!response.ok)
      return json(
        {
          error:
            response.status === 401
              ? "Votre session a expiré. Reconnectez-vous."
              : "Les notifications sont temporairement indisponibles.",
        },
        response.status,
      );
    return json(await response.json());
  } catch {
    return json(
      {
        error:
          "Les notifications sont temporairement indisponibles. Réessayez.",
      },
      503,
    );
  }
}
