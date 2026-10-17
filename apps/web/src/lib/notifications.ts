import "server-only";
import { json, session, validMutation } from "@/lib/auth";
import { rateLimit } from "@/lib/rate";
export async function notificationRequest(
  request: Request,
  resource?: "unread-count" | "read",
  id?: string,
) {
  const rejection = rateLimit(request);
  if (rejection) return rejection;
  try {
    const data = await session();
    if (!data)
      return json({ error: "Votre session a expiré. Reconnectez-vous." }, 401);
    if (resource === "read") {
      if (!validMutation(request, data, request.headers.get("x-csrf-token")))
        return json({ error: "Requête refusée" }, 403);
      if (
        !id ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          id,
        )
      )
        return json({ error: "Notification introuvable" }, 404);
    }
    const url = new URL(
      resource === "read"
        ? `/notifications/${id}/read`
        : resource === "unread-count"
          ? "/notifications/unread-count"
          : "/notifications",
      process.env.API_ORIGIN || "http://localhost:8080",
    );
    if (!resource) {
      url.searchParams.set(
        "page",
        new URL(request.url).searchParams.get("page") || "0",
      );
      url.searchParams.set("size", "20");
    }
    const response = await fetch(url, {
      method: resource === "read" ? "PUT" : "GET",
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
              : response.status === 404
                ? "Notification introuvable"
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
